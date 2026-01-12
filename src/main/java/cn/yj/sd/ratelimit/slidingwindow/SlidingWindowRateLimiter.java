package cn.yj.sd.ratelimit.slidingwindow;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * 高并发滑动窗口限流器
 * 
 * <p>
 * 设计原理：
 * 将时间窗口划分为多个小的时间片段（子窗口），每个子窗口独立计数。
 * 通过滑动的方式，动态计算当前时间窗口内的请求总数，从而实现平滑的限流效果。
 * 
 * <p>
 * 相比固定窗口的优势：
 * 1. 解决了固定窗口边界突发的问题（临界问题）
 * 2. 限流更加平滑和精确
 * 3. 可以根据子窗口数量调整精度
 * 
 * <p>
 * 线程安全：
 * 使用 ConcurrentHashMap + LongAdder 实现高并发下的线程安全
 * 
 * @author yaojun
 */
public class SlidingWindowRateLimiter {

    /**
     * 时间窗口大小（毫秒）
     */
    private final long windowSizeMs;

    /**
     * 子窗口数量（窗口精度）
     */
    private final int subWindowCount;

    /**
     * 每个子窗口的时间大小（毫秒）
     */
    private final long subWindowSizeMs;

    /**
     * 窗口内允许的最大请求数
     */
    private final long maxPermits;

    /**
     * 子窗口计数器映射
     * key: 子窗口索引（基于时间计算）
     * value: 该子窗口内的请求计数
     */
    private final ConcurrentHashMap<Long, LongAdder> subWindowCounters;

    /**
     * 当前总请求数（用于快速判断，减少遍历开销）
     */
    private final AtomicLong totalCount;

    /**
     * 上一次清理过期子窗口的时间
     */
    private volatile long lastCleanupTime;

    /**
     * 清理间隔（毫秒），默认为窗口大小
     */
    private final long cleanupIntervalMs;

    /**
     * 构造滑动窗口限流器
     *
     * @param windowSizeMs   时间窗口大小（毫秒），例如 1000 表示1秒
     * @param subWindowCount 子窗口数量，数量越多精度越高，但内存开销也越大
     * @param maxPermits     窗口内允许的最大请求数
     */
    public SlidingWindowRateLimiter(long windowSizeMs, int subWindowCount, long maxPermits) {
        if (windowSizeMs <= 0) {
            throw new IllegalArgumentException("windowSizeMs must be positive");
        }
        if (subWindowCount <= 0) {
            throw new IllegalArgumentException("subWindowCount must be positive");
        }
        if (maxPermits <= 0) {
            throw new IllegalArgumentException("maxPermits must be positive");
        }

        this.windowSizeMs = windowSizeMs;
        this.subWindowCount = subWindowCount;
        this.subWindowSizeMs = windowSizeMs / subWindowCount;
        this.maxPermits = maxPermits;
        this.subWindowCounters = new ConcurrentHashMap<>();
        this.totalCount = new AtomicLong(0);
        this.lastCleanupTime = System.currentTimeMillis();
        this.cleanupIntervalMs = windowSizeMs;
    }

    /**
     * 便捷构造方法：创建每秒限流器
     *
     * @param maxPermitsPerSecond 每秒允许的最大请求数
     * @return 滑动窗口限流器实例
     */
    public static SlidingWindowRateLimiter createPerSecond(long maxPermitsPerSecond) {
        // 每秒窗口，10个子窗口（每100ms一个子窗口）
        return new SlidingWindowRateLimiter(1000, 10, maxPermitsPerSecond);
    }

    /**
     * 便捷构造方法：创建每分钟限流器
     *
     * @param maxPermitsPerMinute 每分钟允许的最大请求数
     * @return 滑动窗口限流器实例
     */
    public static SlidingWindowRateLimiter createPerMinute(long maxPermitsPerMinute) {
        // 每分钟窗口，60个子窗口（每1秒一个子窗口）
        return new SlidingWindowRateLimiter(60_000, 60, maxPermitsPerMinute);
    }

    /**
     * 尝试获取一个许可
     *
     * @return 如果获取成功返回true，否则返回false
     */
    public boolean tryAcquire() {
        return tryAcquire(1);
    }

    /**
     * 尝试获取指定数量的许可
     *
     * @param permits 需要获取的许可数量
     * @return 如果获取成功返回true，否则返回false
     */
    public boolean tryAcquire(int permits) {
        if (permits <= 0) {
            throw new IllegalArgumentException("permits must be positive");
        }

        long now = System.currentTimeMillis();

        // 定期清理过期的子窗口
        cleanupExpiredSubWindowsIfNeeded(now);

        // 计算当前时间所属的子窗口索引
        long currentSubWindowIndex = now / subWindowSizeMs;

        // 计算窗口起始时间对应的子窗口索引
        long windowStartTime = now - windowSizeMs;
        long startSubWindowIndex = windowStartTime / subWindowSizeMs;

        // 统计当前窗口内的请求总数
        long currentCount = countRequestsInWindow(startSubWindowIndex, currentSubWindowIndex);

        // 判断是否超过限制
        if (currentCount + permits > maxPermits) {
            return false;
        }

        // 获取或创建当前子窗口的计数器并增加计数
        LongAdder counter = subWindowCounters.computeIfAbsent(currentSubWindowIndex, k -> new LongAdder());
        counter.add(permits);
        totalCount.addAndGet(permits);

        return true;
    }

    /**
     * 统计指定范围内子窗口的请求总数
     *
     * @param startIndex 起始子窗口索引（包含）
     * @param endIndex   结束子窗口索引（包含）
     * @return 请求总数
     */
    private long countRequestsInWindow(long startIndex, long endIndex) {
        long count = 0;
        for (long i = startIndex; i <= endIndex; i++) {
            LongAdder counter = subWindowCounters.get(i);
            if (counter != null) {
                count += counter.sum();
            }
        }
        return count;
    }

    /**
     * 清理过期的子窗口（如果需要）
     * 
     * <p>
     * 采用惰性清理策略，避免频繁清理带来的性能开销
     *
     * @param now 当前时间
     */
    private void cleanupExpiredSubWindowsIfNeeded(long now) {
        if (now - lastCleanupTime < cleanupIntervalMs) {
            return;
        }

        // 使用 CAS 避免多线程重复清理
        synchronized (this) {
            if (now - lastCleanupTime < cleanupIntervalMs) {
                return;
            }

            long windowStartTime = now - windowSizeMs;
            long startSubWindowIndex = windowStartTime / subWindowSizeMs;

            // 移除过期的子窗口
            subWindowCounters.entrySet().removeIf(entry -> {
                if (entry.getKey() < startSubWindowIndex) {
                    // 从总计数中减去过期窗口的计数
                    totalCount.addAndGet(-entry.getValue().sum());
                    return true;
                }
                return false;
            });

            lastCleanupTime = now;
        }
    }

    /**
     * 获取当前窗口内的请求数量
     *
     * @return 当前窗口内的请求数量
     */
    public long getCurrentCount() {
        long now = System.currentTimeMillis();
        long currentSubWindowIndex = now / subWindowSizeMs;
        long windowStartTime = now - windowSizeMs;
        long startSubWindowIndex = windowStartTime / subWindowSizeMs;
        return countRequestsInWindow(startSubWindowIndex, currentSubWindowIndex);
    }

    /**
     * 获取剩余可用的许可数量
     *
     * @return 剩余可用许可数量
     */
    public long getAvailablePermits() {
        return Math.max(0, maxPermits - getCurrentCount());
    }

    /**
     * 获取配置信息
     *
     * @return 配置信息字符串
     */
    public String getConfig() {
        return String.format(
                "SlidingWindowRateLimiter[windowSize=%dms, subWindowCount=%d, subWindowSize=%dms, maxPermits=%d]",
                windowSizeMs, subWindowCount, subWindowSizeMs, maxPermits);
    }

    /**
     * 重置限流器状态
     */
    public void reset() {
        subWindowCounters.clear();
        totalCount.set(0);
        lastCleanupTime = System.currentTimeMillis();
    }

    // ==================== Getters ====================

    public long getWindowSizeMs() {
        return windowSizeMs;
    }

    public int getSubWindowCount() {
        return subWindowCount;
    }

    public long getSubWindowSizeMs() {
        return subWindowSizeMs;
    }

    public long getMaxPermits() {
        return maxPermits;
    }
}
