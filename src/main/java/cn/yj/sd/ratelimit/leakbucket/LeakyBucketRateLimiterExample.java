package cn.yj.sd.ratelimit.leakbucket;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 漏桶限流器使用示例
 * 
 * @author YaoJun
 * @since 2026-01-09
 */
public class LeakyBucketRateLimiterExample {

    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");

    public static void main(String[] args) throws Exception {
        System.out.println("=".repeat(60));
        System.out.println("漏桶限流器 (Leaky Bucket) 演示 - 水量懒更新实现");
        System.out.println("=".repeat(60));

        // 演示1：基本原理演示
        demoBasicPrinciple();

        // 演示2：突发流量处理
        demoBurstTraffic();

        // 演示3：高并发测试
        demoConcurrentRequests();

        System.out.println("\n所有演示完成！");
    }

    /**
     * 演示1：基本原理演示 - 直观理解水量变化
     */
    private static void demoBasicPrinciple() {
        System.out.println("\n【演示1】基本原理 - 直观理解水量变化");
        System.out.println("-".repeat(50));

        // 创建限流器：容量10，每秒漏出2个
        LeakyBucketRateLimiter bucket = new LeakyBucketRateLimiter(10, 2);

        log("创建漏桶: 容量=10, 漏出速率=2/秒");
        log("初始状态: " + bucket);

        // 加入5个请求
        log("\n>>> 加入5个请求...");
        for (int i = 1; i <= 5; i++) {
            boolean success = bucket.tryAcquire();
            log(String.format("  请求 #%d: %s | %s", i, success ? "✓成功" : "✗失败", bucket));
        }

        // 等待1秒，观察漏出
        log("\n>>> 等待1秒，让水漏出...");
        sleep(1000);
        log("  1秒后: " + bucket);
        log("  预期漏出: 2个 (2/秒 x 1秒)");

        // 再加入请求
        log("\n>>> 再加入6个请求...");
        int success = 0, fail = 0;
        for (int i = 1; i <= 6; i++) {
            if (bucket.tryAcquire()) {
                success++;
            } else {
                fail++;
            }
        }
        log(String.format("  成功: %d, 失败: %d | %s", success, fail, bucket));
    }

    /**
     * 演示2：突发流量处理
     */
    private static void demoBurstTraffic() throws InterruptedException {
        System.out.println("\n【演示2】突发流量处理");
        System.out.println("-".repeat(50));

        // 创建限流器：容量20，每秒漏出10个
        LeakyBucketRateLimiter bucket = new LeakyBucketRateLimiter(20, 10);

        log("创建漏桶: 容量=20, 漏出速率=10/秒");

        // 第一波：突发50个请求
        log("\n[第一波] 瞬间涌入50个请求:");
        int wave1Success = 0;
        for (int i = 0; i < 50; i++) {
            if (bucket.tryAcquire())
                wave1Success++;
        }
        log(String.format("  成功: %d, 拒绝: %d", wave1Success, 50 - wave1Success));
        log("  " + bucket);

        // 等待1秒
        log("\n等待1秒 (预期漏出10个)...");
        Thread.sleep(1000);
        log("  " + bucket);

        // 第二波
        log("\n[第二波] 再次涌入30个请求:");
        int wave2Success = 0;
        for (int i = 0; i < 30; i++) {
            if (bucket.tryAcquire())
                wave2Success++;
        }
        log(String.format("  成功: %d, 拒绝: %d", wave2Success, 30 - wave2Success));
        log("  " + bucket);

        // 等待桶完全清空
        log("\n等待3秒让桶完全清空...");
        Thread.sleep(3000);
        log("  " + bucket);
    }

    /**
     * 演示3：高并发测试
     */
    private static void demoConcurrentRequests() throws InterruptedException {
        System.out.println("\n【演示3】高并发测试 - 多线程竞争");
        System.out.println("-".repeat(50));

        // 创建限流器：容量100，每秒漏出50个
        LeakyBucketRateLimiter bucket = new LeakyBucketRateLimiter(100, 50);

        int threadCount = 50;
        int requestsPerThread = 10;
        int totalRequests = threadCount * requestsPerThread;

        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failCount = new AtomicInteger(0);
        CountDownLatch latch = new CountDownLatch(threadCount);

        ExecutorService executor = Executors.newFixedThreadPool(threadCount);

        log(String.format("启动 %d 线程，每线程 %d 请求，共 %d 请求",
                threadCount, requestsPerThread, totalRequests));
        log("漏桶: " + bucket);

        long startTime = System.currentTimeMillis();

        for (int t = 0; t < threadCount; t++) {
            executor.submit(() -> {
                try {
                    for (int i = 0; i < requestsPerThread; i++) {
                        if (bucket.tryAcquire()) {
                            successCount.incrementAndGet();
                        } else {
                            failCount.incrementAndGet();
                        }
                    }
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await();
        long elapsed = System.currentTimeMillis() - startTime;
        executor.shutdown();

        log("\n结果统计:");
        log(String.format("  总请求: %d | 成功: %d | 拒绝: %d",
                totalRequests, successCount.get(), failCount.get()));
        log(String.format("  耗时: %dms", elapsed));
        log("  " + bucket);
    }

    private static void log(String message) {
        String timestamp = LocalDateTime.now().format(TIME_FORMATTER);
        System.out.println("[" + timestamp + "] " + message);
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
