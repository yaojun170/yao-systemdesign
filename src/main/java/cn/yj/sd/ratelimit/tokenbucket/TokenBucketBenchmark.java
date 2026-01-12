package cn.yj.sd.ratelimit.tokenbucket;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * V1 vs V2 性能对比测试
 * 
 * <p>
 * 对比两个版本在高并发场景下的性能差异：
 * </p>
 * <ul>
 * <li>V1: AtomicReference&lt;BucketState&gt; - CAS 失败会产生垃圾对象</li>
 * <li>V2: AtomicLong x 2 - 零对象分配</li>
 * </ul>
 * 
 * @author YaoJun
 * @since 2026-01-09
 */
public class TokenBucketBenchmark {

    public static void main(String[] args) throws Exception {
        System.out.println("═══════════════════════════════════════════════════════════");
        System.out.println("              令牌桶限流器 V1 vs V2 性能对比");
        System.out.println("═══════════════════════════════════════════════════════════\n");

        // 预热 JVM
        System.out.println("预热 JVM...");
        warmUp();
        System.out.println("预热完成\n");

        // 测试参数
        int[] threadCounts = { 4, 8, 16, 32 };
        int requestsPerThread = 100_000;

        for (int threadCount : threadCounts) {
            System.out.println("─────────────────────────────────────────────────────────");
            System.out.printf("并发线程数: %d, 每线程请求数: %d, 总请求: %d%n",
                    threadCount, requestsPerThread, threadCount * requestsPerThread);
            System.out.println("─────────────────────────────────────────────────────────");

            // 测试 V1
            BenchmarkResult v1Result = benchmarkV1(threadCount, requestsPerThread);
            System.out.printf("V1 (AtomicReference): %,d 请求/秒 | 成功率: %.1f%%%n",
                    v1Result.throughput, v1Result.successRate * 100);

            // 强制 GC，获取更公平的测试
            System.gc();
            Thread.sleep(100);

            // 测试 V2
            BenchmarkResult v2Result = benchmarkV2(threadCount, requestsPerThread);
            System.out.printf("V2 (AtomicLong x 2):  %,d 请求/秒 | 成功率: %.1f%%%n",
                    v2Result.throughput, v2Result.successRate * 100);

            // 性能提升
            double improvement = ((double) v2Result.throughput / v1Result.throughput - 1) * 100;
            System.out.printf("性能提升: %.1f%%%n%n", improvement);

            System.gc();
            Thread.sleep(100);
        }

        System.out.println("═══════════════════════════════════════════════════════════");
        System.out.println("                      测试完成");
        System.out.println("═══════════════════════════════════════════════════════════");
    }

    private static void warmUp() throws Exception {
        TokenBucketRateLimiter v1 = new TokenBucketRateLimiter(1_000_000, 1_000_000);
        TokenBucketRateLimiterV2 v2 = new TokenBucketRateLimiterV2(1_000_000, 1_000_000);

        for (int i = 0; i < 100_000; i++) {
            v1.tryAcquire();
            v2.tryAcquire();
        }
    }

    private static BenchmarkResult benchmarkV1(int threadCount, int requestsPerThread) throws Exception {
        // 高容量、高速率，确保大部分请求能成功，最大化 CAS 竞争
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(1_000_000, 1_000_000);

        return runBenchmark(threadCount, requestsPerThread, limiter::tryAcquire);
    }

    private static BenchmarkResult benchmarkV2(int threadCount, int requestsPerThread) throws Exception {
        TokenBucketRateLimiterV2 limiter = new TokenBucketRateLimiterV2(1_000_000, 1_000_000);

        return runBenchmark(threadCount, requestsPerThread, limiter::tryAcquire);
    }

    private static BenchmarkResult runBenchmark(int threadCount, int requestsPerThread,
            Runnable acquireAction) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch endLatch = new CountDownLatch(threadCount);
        AtomicInteger successCount = new AtomicInteger(0);

        for (int t = 0; t < threadCount; t++) {
            executor.submit(() -> {
                try {
                    startLatch.await();
                    for (int i = 0; i < requestsPerThread; i++) {
                        try {
                            acquireAction.run();
                            successCount.incrementAndGet();
                        } catch (Exception e) {
                            // ignore
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    endLatch.countDown();
                }
            });
        }

        long startTime = System.nanoTime();
        startLatch.countDown();
        endLatch.await();
        long duration = System.nanoTime() - startTime;

        executor.shutdown();

        int totalRequests = threadCount * requestsPerThread;
        long throughput = (long) (totalRequests * 1_000_000_000L / duration);
        double successRate = (double) successCount.get() / totalRequests;

        return new BenchmarkResult(throughput, successRate);
    }

    private static class BenchmarkResult {
        final long throughput; // 请求/秒
        final double successRate; // 成功率

        BenchmarkResult(long throughput, double successRate) {
            this.throughput = throughput;
            this.successRate = successRate;
        }
    }
}
