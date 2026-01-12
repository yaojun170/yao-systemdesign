package cn.yj.sd.ratelimit.slidingwindow;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 滑动窗口限流器使用示例
 * 
 * <p>
 * 演示内容：
 * 1. 基础用法
 * 2. 高并发场景测试
 * 3. 边界突发测试（对比固定窗口的优势）
 * 4. 不同时间粒度的限流
 * 
 * @author yaojun
 */
public class SlidingWindowRateLimiterExample {

    public static void main(String[] args) throws Exception {
        System.out.println("========== 滑动窗口限流器示例 ==========\n");

        // 1. 基础用法演示
        basicUsageDemo();

        // 2. 高并发压力测试
        highConcurrencyTest();

        // 3. 边界突发测试
        burstAtBoundaryTest();

        // 4. 每分钟限流演示
        perMinuteLimitDemo();
    }

    /**
     * 基础用法演示
     */
    private static void basicUsageDemo() throws InterruptedException {
        System.out.println("【Demo 1】基础用法演示");
        System.out.println("----------------------------------------");

        // 创建限流器：每秒最多允许5个请求
        SlidingWindowRateLimiter limiter = SlidingWindowRateLimiter.createPerSecond(5);
        System.out.println("配置: " + limiter.getConfig());
        System.out.println();

        // 模拟请求
        for (int i = 1; i <= 10; i++) {
            boolean acquired = limiter.tryAcquire();
            System.out.printf("请求 %2d: %s | 当前计数: %d | 剩余许可: %d%n",
                    i,
                    acquired ? "✓ 通过" : "✗ 拒绝",
                    limiter.getCurrentCount(),
                    limiter.getAvailablePermits());

            Thread.sleep(100); // 每100ms发送一个请求
        }

        System.out.println();
        System.out.println("等待1秒后窗口滑动...\n");
        Thread.sleep(1000);

        // 窗口滑动后继续请求
        for (int i = 11; i <= 15; i++) {
            boolean acquired = limiter.tryAcquire();
            System.out.printf("请求 %2d: %s | 当前计数: %d | 剩余许可: %d%n",
                    i,
                    acquired ? "✓ 通过" : "✗ 拒绝",
                    limiter.getCurrentCount(),
                    limiter.getAvailablePermits());
        }

        System.out.println("\n");
    }

    /**
     * 高并发压力测试
     */
    private static void highConcurrencyTest() throws InterruptedException {
        System.out.println("【Demo 2】高并发压力测试");
        System.out.println("----------------------------------------");

        // 创建限流器：每秒最多允许100个请求
        SlidingWindowRateLimiter limiter = SlidingWindowRateLimiter.createPerSecond(100);
        System.out.println("配置: " + limiter.getConfig());

        int threadCount = 50;
        int requestsPerThread = 10;
        int totalRequests = threadCount * requestsPerThread;

        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch endLatch = new CountDownLatch(threadCount);

        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failCount = new AtomicInteger(0);

        System.out.printf("启动 %d 个线程，每个线程发送 %d 个请求，共 %d 个请求%n",
                threadCount, requestsPerThread, totalRequests);
        System.out.println("限流阈值: 100 请求/秒");
        System.out.println();

        // 启动所有线程
        for (int t = 0; t < threadCount; t++) {
            executor.submit(() -> {
                try {
                    startLatch.await(); // 等待统一开始
                    for (int i = 0; i < requestsPerThread; i++) {
                        if (limiter.tryAcquire()) {
                            successCount.incrementAndGet();
                        } else {
                            failCount.incrementAndGet();
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    endLatch.countDown();
                }
            });
        }

        long startTime = System.currentTimeMillis();
        startLatch.countDown(); // 统一开始
        endLatch.await(); // 等待所有线程完成
        long endTime = System.currentTimeMillis();

        System.out.printf("执行完成，耗时: %d ms%n", endTime - startTime);
        System.out.printf("成功请求: %d%n", successCount.get());
        System.out.printf("拒绝请求: %d%n", failCount.get());
        System.out.printf("通过率: %.2f%%%n", (double) successCount.get() / totalRequests * 100);
        System.out.println();

        // 由于是瞬时并发，预期成功数应该接近100
        if (successCount.get() <= 100 && successCount.get() >= 90) {
            System.out.println("✓ 限流效果符合预期！");
        } else {
            System.out.println("△ 限流结果略有偏差，可能由于时间窗口边界");
        }

        executor.shutdown();
        executor.awaitTermination(5, TimeUnit.SECONDS);

        System.out.println("\n");
    }

    /**
     * 边界突发测试
     * 演示滑动窗口相比固定窗口在边界处的优势
     */
    private static void burstAtBoundaryTest() throws InterruptedException {
        System.out.println("【Demo 3】边界突发测试（滑动窗口优势）");
        System.out.println("----------------------------------------");
        System.out.println("场景: 在窗口边界处集中发送请求，验证滑动窗口的平滑限流效果");
        System.out.println();

        // 创建限流器：每秒最多10个请求，20个子窗口（每50ms一个）
        SlidingWindowRateLimiter limiter = new SlidingWindowRateLimiter(1000, 20, 10);
        System.out.println("配置: " + limiter.getConfig());
        System.out.println();

        // 第一批请求：在窗口中间发送
        System.out.println("第一批请求（窗口中间）:");
        int batch1Success = 0;
        for (int i = 0; i < 8; i++) {
            if (limiter.tryAcquire()) {
                batch1Success++;
            }
        }
        System.out.printf("  发送 8 个请求，成功 %d 个%n", batch1Success);

        // 等待一小段时间（模拟接近窗口边界）
        Thread.sleep(600);

        // 第二批请求：尝试突发
        System.out.println("\n第二批请求（600ms后）:");
        int batch2Success = 0;
        for (int i = 0; i < 8; i++) {
            if (limiter.tryAcquire()) {
                batch2Success++;
            }
        }
        System.out.printf("  发送 8 个请求，成功 %d 个%n", batch2Success);

        System.out.println();
        System.out.println("分析:");
        System.out.println("  - 固定窗口算法: 在窗口边界处可能允许 16 个请求通过");
        System.out.println("  - 滑动窗口算法: 总共只允许 " + (batch1Success + batch2Success) + " 个请求通过");
        System.out.println("  - 滑动窗口有效避免了边界突发问题！");

        System.out.println("\n");
    }

    /**
     * 每分钟限流演示
     */
    private static void perMinuteLimitDemo() {
        System.out.println("【Demo 4】每分钟限流配置示例");
        System.out.println("----------------------------------------");

        // 创建每分钟限流器
        SlidingWindowRateLimiter minuteLimiter = SlidingWindowRateLimiter.createPerMinute(1000);
        System.out.println("每分钟限流配置: " + minuteLimiter.getConfig());

        // 自定义配置：5秒窗口，50个子窗口（每100ms），最多允许20个请求
        SlidingWindowRateLimiter customLimiter = new SlidingWindowRateLimiter(5000, 50, 20);
        System.out.println("自定义限流配置: " + customLimiter.getConfig());

        System.out.println("\n使用建议:");
        System.out.println("  1. 子窗口数量越多，精度越高，但内存开销也越大");
        System.out.println("  2. 推荐子窗口大小在 50ms-500ms 之间");
        System.out.println("  3. 对于高 QPS 场景，建议使用 LongAdder 的实现（已内置）");
        System.out.println("  4. 生产环境建议配合监控，实时观察限流效果");

        System.out.println("\n========== 示例完成 ==========");
    }
}
