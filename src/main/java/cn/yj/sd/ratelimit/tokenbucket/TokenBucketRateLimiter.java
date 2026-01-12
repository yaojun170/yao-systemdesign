package cn.yj.sd.ratelimit.tokenbucket;

import java.util.concurrent.atomic.AtomicReference;

/**
 * 令牌桶限流器 - 基于令牌懒更新的高性能实现
 * 
 * <h2>核心思想（物理模型）</h2>
 * 
 * <pre>
 *   令牌以固定速率补充
 *            ↓
 *     ┌─────────────┐
 *     │  ●  ●  ●  ● │  ← 当前令牌数 (tokens)
 *     │  ●  ●  ●  ● │
 *     │             │  ← 剩余空间 (capacity - tokens)
 *     └──────┬──────┘
 *            ↓
 *     成功获取令牌 → 放行请求
 * </pre>
 * 
 * <h2>令牌桶 vs 漏桶</h2>
 * <ul>
 * <li>漏桶：以固定速率"漏出"请求，平滑输出，不允许突发</li>
 * <li>令牌桶：以固定速率"补充"令牌，允许累积令牌应对突发流量</li>
 * </ul>
 * 
 * <h2>实现原理</h2>
 * <ul>
 * <li>维护「当前令牌数」和「上次更新时间」</li>
 * <li>每次请求时，先计算从上次到现在应该补充了多少令牌（懒更新）</li>
 * <li>然后判断当前令牌是否足够</li>
 * </ul>
 * 
 * <h2>线程安全</h2>
 * <ul>
 * <li>使用 AtomicReference + CAS 实现无锁并发</li>
 * <li>将令牌数和时间戳封装为不可变对象，原子更新</li>
 * </ul>
 * 
 * <h2>整数令牌的饥饿问题修复</h2>
 * <p>
 * 使用 int 类型存储令牌时，需要保留未产生令牌的时间余数，
 * 避免高频请求下时间被白白消费。
 * </p>
 * 
 * @author YaoJun
 * @since 2026-01-09
 */
public class TokenBucketRateLimiter {

    /**
     * 桶的容量（最大令牌数）
     */
    private final int capacity;

    /**
     * 令牌补充速率：每秒生成多少令牌
     */
    private final double refillRatePerSecond;

    /**
     * 桶的状态（令牌数 + 上次更新时间）
     * 使用 AtomicReference 保证原子性更新
     */
    private final AtomicReference<BucketState> state;

    /**
     * 桶的状态 - 不可变对象
     */
    private static class BucketState {
        /** 当前令牌数（整数） */
        final int tokens;
        /** 上次更新时间（纳秒） */
        final long lastRefillTime;

        BucketState(int tokens, long lastRefillTime) {
            this.tokens = tokens;
            this.lastRefillTime = lastRefillTime;
        }
    }

    /**
     * 构造令牌桶限流器
     *
     * @param capacity            桶容量（最大令牌数），如 100 表示最多累积100个令牌
     * @param refillRatePerSecond 令牌补充速率（每秒生成的令牌数），如 10 表示每秒补充10个
     */
    public TokenBucketRateLimiter(int capacity, double refillRatePerSecond) {
        this(capacity, refillRatePerSecond, true);
    }

    /**
     * 构造令牌桶限流器
     *
     * @param capacity            桶容量（最大令牌数），如 100 表示最多累积100个令牌
     * @param refillRatePerSecond 令牌补充速率（每秒生成的令牌数），如 10 表示每秒补充10个
     * @param startWithFullBucket 是否以满桶状态启动（true = 初始时桶满，false = 初始时桶空）
     */
    public TokenBucketRateLimiter(int capacity, double refillRatePerSecond, boolean startWithFullBucket) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("容量必须为正数，当前值: " + capacity);
        }
        if (refillRatePerSecond <= 0) {
            throw new IllegalArgumentException("补充速率必须为正数，当前值: " + refillRatePerSecond);
        }

        this.capacity = capacity;
        this.refillRatePerSecond = refillRatePerSecond;

        // 初始状态：根据参数决定是满桶还是空桶
        int initialTokens = startWithFullBucket ? capacity : 0;
        this.state = new AtomicReference<>(new BucketState(initialTokens, System.nanoTime()));
    }

    /**
     * 尝试获取一个令牌（非阻塞）
     * 
     * <p>
     * 核心逻辑：
     * <ol>
     * <li>计算从上次更新到现在应该补充的令牌数</li>
     * <li>更新当前令牌数 = min(旧令牌 + 补充令牌, 容量)</li>
     * <li>判断：当前令牌 >= 1 → 成功消费，否则失败</li>
     * </ol>
     *
     * @return true = 成功（获取令牌），false = 失败（令牌不足）
     */
    public boolean tryAcquire() {
        return tryAcquire(1);
    }

    /**
     * 尝试获取多个令牌（非阻塞）
     * 
     * <p>
     * 关键修复：只消费产生了整数令牌的时间，余数时间保留
     * </p>
     * 
     * <pre>
     * 例如：refillRate=10/秒，elapsedNs=50ms
     *   refillTokens = 50ms * 10/s = 0.5 → 取整为 0
     *   consumedNanos = 0 / 10 * 1e9 = 0ns
     *   newTime = lastTime + 0 = lastTime（时间保留！）
     * 
     * 再过 50ms：elapsedNs = 100ms
     *   refillTokens = 100ms * 10/s = 1
     *   consumedNanos = 1 / 10 * 1e9 = 100ms
     *   newTime = lastTime + 100ms
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
            // 1. 读取当前状态
            BucketState currentState = state.get();
            long now = System.nanoTime();

            // 2. 计算时间差
            long elapsedNanos = now - currentState.lastRefillTime;

            int currentTokens = currentState.tokens;
            long newRefillTime = currentState.lastRefillTime; // 默认保持原时间

            if (elapsedNanos > 0) {
                // 计算应该补充的整数令牌数
                double elapsedSeconds = elapsedNanos / 1_000_000_000.0;
                int refillTokens = (int) (elapsedSeconds * refillRatePerSecond);

                if (refillTokens > 0) {
                    // 只有产生了令牌，才更新
                    currentTokens = Math.min(capacity, currentState.tokens + refillTokens);

                    // 关键：只消费产生了整数令牌的时间，余数保留
                    // consumedNanos = refillTokens / refillRatePerSecond * 1e9
                    long consumedNanos = (long) (refillTokens / refillRatePerSecond * 1_000_000_000.0);
                    newRefillTime = currentState.lastRefillTime + consumedNanos;
                }
                // 如果 refillTokens == 0，时间不更新，余数时间保留给下次
            }

            // 3. 判断令牌是否足够
            if (currentTokens < permits) {
                return false; // 令牌不足，拒绝请求
            }

            // 4. 计算消费后的令牌数
            int newTokens = currentTokens - permits;

            // 5. CAS 更新状态
            BucketState newState = new BucketState(newTokens, newRefillTime);
            if (state.compareAndSet(currentState, newState)) {
                return true; // 成功获取令牌
            }

            // CAS 失败，说明有其他线程并发修改，重试
            Thread.onSpinWait();
        }
    }

    /**
     * 阻塞式获取一个令牌
     * 
     * <p>
     * 如果当前没有可用令牌，会等待直到有令牌可用
     * </p>
     *
     * @throws InterruptedException 如果等待过程中被中断
     */
    public void acquire() throws InterruptedException {
        acquire(1);
    }

    /**
     * 阻塞式获取多个令牌
     *
     * @param permits 需要的令牌数量
     * @throws InterruptedException 如果等待过程中被中断
     */
    public void acquire(int permits) throws InterruptedException {
        if (permits <= 0) {
            throw new IllegalArgumentException("许可数必须为正数，当前值: " + permits);
        }

        while (!tryAcquire(permits)) {
            // 计算需要等待的时间
            long waitNanos = calculateWaitTime(permits);
            if (waitNanos > 0) {
                Thread.sleep(waitNanos / 1_000_000, (int) (waitNanos % 1_000_000));
            }

            // 检查中断
            if (Thread.interrupted()) {
                throw new InterruptedException();
            }
        }
    }

    /**
     * 带超时的获取令牌
     *
     * @param permits       需要的令牌数量
     * @param timeoutMillis 超时时间（毫秒）
     * @return true = 成功获取，false = 超时
     * @throws InterruptedException 如果等待过程中被中断
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
                return false; // 超时
            }

            // 计算需要等待的时间，但不超过剩余超时时间
            long waitNanos = Math.min(calculateWaitTime(permits), remaining);
            if (waitNanos > 0) {
                Thread.sleep(waitNanos / 1_000_000, (int) (waitNanos % 1_000_000));
            }

            // 检查中断
            if (Thread.interrupted()) {
                throw new InterruptedException();
            }
        }

        return true;
    }

    /**
     * 计算获取指定数量令牌需要等待的时间
     *
     * @param permits 需要的令牌数量
     * @return 需要等待的纳秒数
     */
    private long calculateWaitTime(int permits) {
        int currentTokens = getCurrentTokens();
        int tokensNeeded = permits - currentTokens;
        if (tokensNeeded <= 0) {
            return 0;
        }
        // 计算需要等待的时间（纳秒）
        double waitSeconds = tokensNeeded / refillRatePerSecond;
        return (long) (waitSeconds * 1_000_000_000);
    }

    /**
     * 获取当前可用的令牌数
     *
     * @return 当前令牌数（已考虑补充）
     */
    public int getCurrentTokens() {
        BucketState currentState = state.get();
        long now = System.nanoTime();
        long elapsedNanos = now - currentState.lastRefillTime;

        if (elapsedNanos > 0) {
            double elapsedSeconds = elapsedNanos / 1_000_000_000.0;
            int refillTokens = (int) (elapsedSeconds * refillRatePerSecond);
            return Math.min(capacity, currentState.tokens + refillTokens);
        }
        return currentState.tokens;
    }

    /**
     * 获取桶的使用率（已消费比例）
     *
     * @return 使用率（0.0 ~ 1.0），1.0 表示桶空，0.0 表示桶满
     */
    public double getUsageRate() {
        return 1.0 - ((double) getCurrentTokens() / capacity);
    }

    /**
     * 获取桶的容量
     */
    public int getCapacity() {
        return capacity;
    }

    /**
     * 获取令牌补充速率
     */
    public double getRefillRatePerSecond() {
        return refillRatePerSecond;
    }

    /**
     * 重置限流器（将桶填满）
     */
    public void reset() {
        state.set(new BucketState(capacity, System.nanoTime()));
    }

    /**
     * 清空桶（将令牌数置为0）
     */
    public void drain() {
        state.set(new BucketState(0, System.nanoTime()));
    }

    @Override
    public String toString() {
        return String.format(
                "TokenBucket{容量=%d, 当前令牌=%d, 使用率=%.1f%%, 补充速率=%.0f/秒}",
                capacity, getCurrentTokens(), getUsageRate() * 100, refillRatePerSecond);
    }
}
