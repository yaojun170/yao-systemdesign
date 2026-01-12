package cn.yj.sd.ratelimit.tokenbucket;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 令牌桶限流器使用示例
 * 
 * <h2>演示场景</h2>
 * <ol>
 * <li>基本使用：非阻塞获取令牌</li>
 * <li>突发流量处理：令牌桶的核心特性</li>
 * <li>高并发场景：多线程竞争令牌</li>
 * <li>阻塞式获取：等待令牌可用</li>
 * <li>带超时获取：限时等待令牌</li>
 * </ol>
 * 
 * @author YaoJun
 * @since 2026-01-09
 */
public class TokenBucketExample {

    public static void main(String[] args) throws Exception {
        System.out.println("═══════════════════════════════════════════════════════════");
        System.out.println("                 令牌桶限流器 - 使用示例");
        System.out.println("═══════════════════════════════════════════════════════════\n");

        // 示例1: 基本使用
        basicUsageDemo();

        // 示例2: 突发流量处理
        burstTrafficDemo();

        // 示例3: 高并发场景
        highConcurrencyDemo();

        // 示例4: 阻塞式获取
        blockingAcquireDemo();

        // 示例5: 带超时获取
        timeoutAcquireDemo();

        System.out.println("\n═══════════════════════════════════════════════════════════");
        System.out.println("                      所有示例运行完成");
        System.out.println("═══════════════════════════════════════════════════════════");
    }

    /**
     * 示例1: 基本使用
     */
    private static void basicUsageDemo() {
        System.out.println("【示例1】基本使用 - 非阻塞获取令牌");
        System.out.println("─────────────────────────────────────────────");

        // 创建令牌桶：容量10个令牌，每秒补充2个
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(10, 2);

        System.out.println("创建令牌桶: 容量=10, 补充速率=2/秒");
        System.out.println("初始状态: " + limiter);
        System.out.println();

        // 模拟请求
        for (int i = 1; i <= 12; i++) {
            boolean acquired = limiter.tryAcquire();
            System.out.printf("请求 %2d: %s | 剩余令牌: %d%n",
                    i,
                    acquired ? "✓ 成功" : "✗ 拒绝",
                    limiter.getCurrentTokens());
        }

        System.out.println("\n状态: " + limiter);
        System.out.println();
    }

    /**
     * 示例2: 突发流量处理 - 令牌桶的核心特性
     */
    private static void burstTrafficDemo() throws InterruptedException {
        System.out.println("【示例2】突发流量处理 - 令牌桶核心特性");
        System.out.println("─────────────────────────────────────────────");

        // 创建令牌桶：容量20个令牌，每秒补充5个
        // 初始时桶是满的，可以应对突发20个请求
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(20, 5);

        System.out.println("创建令牌桶: 容量=20, 补充速率=5/秒");
        System.out.println("场景：系统空闲后突然来了25个并发请求\n");

        // 第一波突发: 一瞬间来25个请求
        int successCount = 0;
        for (int i = 0; i < 25; i++) {
            if (limiter.tryAcquire()) {
                successCount++;
            }
        }
        System.out.printf("第一波突发(25请求): 成功=%d, 拒绝=%d%n", successCount, 25 - successCount);
        System.out.println("剩余令牌: " + limiter.getCurrentTokens());

        // 等待2秒，让令牌补充
        System.out.println("\n等待2秒让令牌补充...");
        Thread.sleep(2000);

        System.out.printf("2秒后令牌数: %d (应该补充了约10个)%n", limiter.getCurrentTokens());

        // 第二波突发
        successCount = 0;
        for (int i = 0; i < 15; i++) {
            if (limiter.tryAcquire()) {
                successCount++;
            }
        }
        System.out.printf("第二波突发(15请求): 成功=%d, 拒绝=%d%n", successCount, 15 - successCount);
        System.out.println();
    }

    /**
     * 示例3: 高并发场景
     */
    private static void highConcurrencyDemo() throws InterruptedException {
        System.out.println("【示例3】高并发场景 - 多线程竞争令牌");
        System.out.println("─────────────────────────────────────────────");

        // 创建限流器：容量100，每秒补充50个
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(100, 50);

        int threadCount = 10;
        int requestsPerThread = 50;
        int totalRequests = threadCount * requestsPerThread;

        System.out.printf("令牌桶: 容量=100, 补充速率=50/秒%n");
        System.out.printf("并发: %d线程, 每线程%d请求, 共%d请求%n%n",
                threadCount, requestsPerThread, totalRequests);

        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch endLatch = new CountDownLatch(threadCount);
        AtomicInteger successCounter = new AtomicInteger(0);
        AtomicInteger failCounter = new AtomicInteger(0);

        long startTime = System.nanoTime();

        // 启动多个线程并发请求
        for (int t = 0; t < threadCount; t++) {
            final int threadId = t + 1;
            executor.submit(() -> {
                try {
                    startLatch.await(); // 等待所有线程就绪
                    for (int i = 0; i < requestsPerThread; i++) {
                        if (limiter.tryAcquire()) {
                            successCounter.incrementAndGet();
                        } else {
                            failCounter.incrementAndGet();
                        }
                        // 模拟少量处理时间
                        Thread.sleep(5);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    endLatch.countDown();
                }
            });
        }

        // 开始！
        startLatch.countDown();
        endLatch.await();

        long duration = (System.nanoTime() - startTime) / 1_000_000;

        System.out.println("并发测试结果:");
        System.out.printf("  总请求数: %d%n", totalRequests);
        System.out.printf("  成功请求: %d%n", successCounter.get());
        System.out.printf("  拒绝请求: %d%n", failCounter.get());
        System.out.printf("  通过率: %.1f%%%n", (successCounter.get() * 100.0 / totalRequests));
        System.out.printf("  耗时: %dms%n", duration);
        System.out.println("  最终状态: " + limiter);

        executor.shutdown();
        System.out.println();
    }

    /**
     * 示例4: 阻塞式获取
     */
    private static void blockingAcquireDemo() throws InterruptedException {
        System.out.println("【示例4】阻塞式获取 - 等待令牌可用");
        System.out.println("─────────────────────────────────────────────");

        // 创建限流器：容量5，每秒补充2个，初始时桶是空的
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(5, 2, false);

        System.out.println("创建令牌桶: 容量=5, 补充速率=2/秒, 初始为空");
        System.out.printf("初始令牌数: %d%n%n", limiter.getCurrentTokens());

        // 阻塞式获取3个令牌
        System.out.println("尝试阻塞式获取3个令牌...");
        long start = System.currentTimeMillis();
        limiter.acquire(3);
        long waited = System.currentTimeMillis() - start;

        System.out.printf("成功获取3个令牌! 等待时间: %dms (预期约1500ms)%n", waited);
        System.out.println();
    }

    /**
     * 示例5: 带超时获取
     */
    private static void timeoutAcquireDemo() throws InterruptedException {
        System.out.println("【示例5】带超时获取 - 限时等待令牌");
        System.out.println("─────────────────────────────────────────────");

        // 创建限流器：容量5，每秒补充1个，初始为空
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(5, 1, false);

        System.out.println("创建令牌桶: 容量=5, 补充速率=1/秒, 初始为空");

        // 场景1: 超时时间足够
        System.out.println("\n场景1: 超时2秒内获取1个令牌 (预期成功)");
        long start = System.currentTimeMillis();
        boolean success = limiter.tryAcquire(1, 2000);
        long waited = System.currentTimeMillis() - start;
        System.out.printf("结果: %s, 等待时间: %dms%n", success ? "成功" : "超时", waited);

        // 重置为空
        limiter.drain();

        // 场景2: 超时时间不够
        System.out.println("\n场景2: 超时500ms内获取2个令牌 (预期超时)");
        start = System.currentTimeMillis();
        success = limiter.tryAcquire(2, 500);
        waited = System.currentTimeMillis() - start;
        System.out.printf("结果: %s, 等待时间: %dms%n", success ? "成功" : "超时", waited);

        System.out.println();
    }
}
