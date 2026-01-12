package cn.yj.sd.ratelimit.tokenbucket;

import java.util.concurrent.atomic.AtomicLong;

/**
 * 令牌桶限流器 V2 - 修复版：单 AtomicLong 编码方案
 * 
 * <h2>设计思路</h2>
 * <p>
 * 将 tokens（整数部分）和 timestamp 编码到一个 64-bit long 中，
 * 实现真正的原子更新。
 * </p>
 * 
 * <h3>编码格式（64-bit）</h3>
 * 
 * <pre>
 * ┌─────────────────────────────────────────────────────────────┐
 * │  高 20 位: tokens (0 ~ 1,048,575)  │  低 44 位: timestamp    │
 * └─────────────────────────────────────────────────────────────┘
 * </pre>
 * 
 * <ul>
 * <li>tokens: 最大支持约 100 万个令牌容量</li>
 * <li>timestamp: 存储相对时间（毫秒），44位可表示约 557 年</li>
 * </ul>
 * 
 * <h2>适用场景</h2>
 * <ul>
 * <li>需要支持极高并发的场景</li>
 * <li>令牌容量在 100 万以内</li>
 * <li>精度要求不是特别高（毫秒级）</li>
 * </ul>
 * 
 * @author YaoJun
 * @since 2026-01-09
 */
public class TokenBucketRateLimiterV2 {

    // ===== 编码常量 =====
    /** tokens 占用的位数 */
    private static final int TOKENS_BITS = 20;
    /** timestamp 占用的位数 */
    private static final int TIMESTAMP_BITS = 44;
    /** tokens 最大值 (2^20 - 1 = 1,048,575) */
    private static final long MAX_TOKENS = (1L << TOKENS_BITS) - 1;
    /** timestamp 掩码 */
    private static final long TIMESTAMP_MASK = (1L << TIMESTAMP_BITS) - 1;
    /** 精度因子：纳秒转毫秒 */
    private static final long NANOS_PER_MILLI = 1_000_000L;

    // ===== 配置 =====
    private final long capacity;
    private final double refillRatePerSecond;
    private final long baseTimeNs;

    // ===== 状态（单个 AtomicLong 存储 tokens + timestamp） =====
    private final AtomicLong state;

    /**
     * 构造令牌桶限流器
     *
     * @param capacity            桶容量（最大令牌数），必须 <= 1,048,575
     * @param refillRatePerSecond 令牌补充速率（每秒生成的令牌数）
     */
    public TokenBucketRateLimiterV2(double capacity, double refillRatePerSecond) {
        this(capacity, refillRatePerSecond, true);
    }

    /**
     * 构造令牌桶限流器
     *
     * @param capacity            桶容量
     * @param refillRatePerSecond 令牌补充速率
     * @param startWithFullBucket 是否以满桶状态启动
     */
    public TokenBucketRateLimiterV2(double capacity, double refillRatePerSecond, boolean startWithFullBucket) {
        if (capacity <= 0 || capacity > MAX_TOKENS) {
            throw new IllegalArgumentException(
                    "容量必须在 1 ~ " + MAX_TOKENS + " 之间，当前值: " + capacity);
        }
        if (refillRatePerSecond <= 0) {
            throw new IllegalArgumentException("补充速率必须为正数，当前值: " + refillRatePerSecond);
        }

        this.capacity = (long) capacity;
        this.refillRatePerSecond = refillRatePerSecond;
        this.baseTimeNs = System.nanoTime();

        long initialTokens = startWithFullBucket ? this.capacity : 0;
        this.state = new AtomicLong(encode(initialTokens, 0));
    }

    // ===== 编码/解码 =====

    /** 编码 tokens 和 timestamp 到一个 long */
    private static long encode(long tokens, long timestampMs) {
        return (tokens << TIMESTAMP_BITS) | (timestampMs & TIMESTAMP_MASK);
    }

    /** 从 long 解码 tokens */
    private static long decodeTokens(long encoded) {
        return encoded >>> TIMESTAMP_BITS;
    }

    /** 从 long 解码 timestamp（毫秒） */
    private static long decodeTimestampMs(long encoded) {
        return encoded & TIMESTAMP_MASK;
    }

    /** 纳秒转相对毫秒 */
    private long toRelativeMs(long nanoTime) {
        return (nanoTime - baseTimeNs) / NANOS_PER_MILLI;
    }

    // ===== 核心方法 =====

    /**
     * 尝试获取一个令牌（非阻塞）
     */
    public boolean tryAcquire() {
        return tryAcquire(1);
    }

    /**
     * 尝试获取多个令牌（非阻塞，原子操作）
     *
     * <p>
     * 关键修复：只消费产生了整数令牌的时间，余数时间保留
     * </p>
     * 
     * <pre>
     * 例如：refillRate=10/秒，elapsedMs=50ms
     *   refillTokens = 50 * 10 / 1000 = 0 个（整数）
     *   consumedTimeMs = 0 * 1000 / 10 = 0ms
     *   newTimeMs = lastTimeMs + 0 = lastTimeMs（时间不变！）
     * 
     * 再过 50ms：elapsedMs = 100ms
     *   refillTokens = 100 * 10 / 1000 = 1 个
     *   consumedTimeMs = 1 * 1000 / 10 = 100ms
     *   newTimeMs = lastTimeMs + 100ms（只消费产生了1个令牌的时间）
     * </pre>
     *
     * @param permits 需要的令牌数量
     * @return true = 成功，false = 失败
     */
    public boolean tryAcquire(int permits) {
        if (permits <= 0) {
            throw new IllegalArgumentException("许可数必须为正数，当前值: " + permits);
        }

        while (true) {
            // 1. 原子读取当前状态（tokens + timestamp 一起读）
            long currentState = state.get();
            long currentTokens = decodeTokens(currentState);
            long lastTimeMs = decodeTimestampMs(currentState);

            // 2. 计算当前时间和补充的令牌
            long nowMs = toRelativeMs(System.nanoTime());
            long elapsedMs = nowMs - lastTimeMs;

            long newTokens = currentTokens;
            long newTimeMs = lastTimeMs; // 默认保持原时间

            if (elapsedMs > 0) {
                // 计算应该补充的整数令牌数
                long refillTokens = (long) (elapsedMs * refillRatePerSecond / 1000.0);

                if (refillTokens > 0) {
                    // 只有产生了令牌，才更新时间
                    newTokens = Math.min(capacity, currentTokens + refillTokens);

                    // 关键：只消费产生了整数令牌的时间，余数保留
                    // consumedTimeMs = refillTokens / refillRatePerSecond * 1000
                    long consumedTimeMs = (long) (refillTokens * 1000.0 / refillRatePerSecond);
                    newTimeMs = lastTimeMs + consumedTimeMs;
                }
                // 如果 refillTokens == 0，时间不更新，余数时间保留给下次
            }

            // 3. 判断令牌是否足够
            if (newTokens < permits) {
                return false; // 令牌不足
            }

            // 4. 计算消费后的状态
            long tokensAfterConsume = newTokens - permits;
            long newState = encode(tokensAfterConsume, newTimeMs);

            // 5. CAS 原子更新（tokens 和 timestamp 一起更新）
            if (state.compareAndSet(currentState, newState)) {
                return true;
            }

            // CAS 失败，重试
            Thread.onSpinWait();
        }
    }

    /**
     * 阻塞式获取令牌
     */
    public void acquire() throws InterruptedException {
        acquire(1);
    }

    /**
     * 阻塞式获取多个令牌
     */
    public void acquire(int permits) throws InterruptedException {
        if (permits <= 0) {
            throw new IllegalArgumentException("许可数必须为正数，当前值: " + permits);
        }

        while (!tryAcquire(permits)) {
            long waitNanos = calculateWaitTime(permits);
            if (waitNanos > 0) {
                Thread.sleep(waitNanos / 1_000_000, (int) (waitNanos % 1_000_000));
            }
            if (Thread.interrupted()) {
                throw new InterruptedException();
            }
        }
    }

    /**
     * 带超时获取
     */
    public boolean tryAcquire(int permits, long timeoutMillis) throws InterruptedException {
        if (permits <= 0) {
            throw new IllegalArgumentException("许可数必须为正数，当前值: " + permits);
        }
        if (timeoutMillis < 0) {
            throw new IllegalArgumentException("超时时间不能为负数，当前值: " + timeoutMillis);
        }

        long deadline = System.nanoTime() + timeoutMillis * 1_000_000L;

        while (!tryAcquire(permits)) {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) {
                return false;
            }
            long waitNanos = Math.min(calculateWaitTime(permits), remaining);
            if (waitNanos > 0) {
                Thread.sleep(waitNanos / 1_000_000, (int) (waitNanos % 1_000_000));
            }
            if (Thread.interrupted()) {
                throw new InterruptedException();
            }
        }
        return true;
    }

    private long calculateWaitTime(int permits) {
        long currentTokens = getCurrentTokens();
        long tokensNeeded = permits - currentTokens;
        if (tokensNeeded <= 0) {
            return 0;
        }
        return (long) (tokensNeeded / refillRatePerSecond * 1_000_000_000);
    }

    /**
     * 获取当前令牌数
     */
    public long getCurrentTokens() {
        long currentState = state.get();
        long tokens = decodeTokens(currentState);
        long lastTimeMs = decodeTimestampMs(currentState);
        long nowMs = toRelativeMs(System.nanoTime());
        long elapsedMs = nowMs - lastTimeMs;

        if (elapsedMs > 0) {
            long refillTokens = (long) (elapsedMs * refillRatePerSecond / 1000.0);
            return Math.min(capacity, tokens + refillTokens);
        }
        return tokens;
    }

    /**
     * 获取使用率
     */
    public double getUsageRate() {
        return 1.0 - ((double) getCurrentTokens() / capacity);
    }

    public long getCapacity() {
        return capacity;
    }

    public double getRefillRatePerSecond() {
        return refillRatePerSecond;
    }

    public void reset() {
        state.set(encode(capacity, toRelativeMs(System.nanoTime())));
    }

    public void drain() {
        state.set(encode(0, toRelativeMs(System.nanoTime())));
    }

    @Override
    public String toString() {
        return String.format(
                "TokenBucketV2{容量=%d, 当前令牌=%d, 使用率=%.1f%%, 补充速率=%.0f/秒}",
                capacity, getCurrentTokens(), getUsageRate() * 100, refillRatePerSecond);
    }
}
