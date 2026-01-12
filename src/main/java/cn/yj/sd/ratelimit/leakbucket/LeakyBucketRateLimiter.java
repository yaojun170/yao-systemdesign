package cn.yj.sd.ratelimit.leakbucket;

import java.util.concurrent.atomic.AtomicReference;

/**
 * 漏桶限流器 - 基于水量懒更新的直观实现
 * 
 * <h2>核心思想（物理模型）</h2>
 * 
 * <pre>
 *     请求入水
 *        ↓
 *   ┌─────────┐
 *   │ ███████ │  ← 当前水量 (water)
 *   │ ███████ │
 *   │         │  ← 剩余容量 (capacity - water)
 *   └────┬────┘
 *        ↓
 *     固定速率漏出
 * </pre>
 * 
 * <h2>实现原理</h2>
 * <ul>
 * <li>维护「当前水量」和「上次更新时间」</li>
 * <li>每次请求时，先计算从上次到现在漏掉了多少水（懒更新）</li>
 * <li>然后判断加入新请求后是否会溢出</li>
 * </ul>
 * 
 * <h2>线程安全</h2>
 * <ul>
 * <li>使用 AtomicReference + CAS 实现无锁并发</li>
 * <li>将水量和时间戳封装为不可变对象，原子更新</li>
 * </ul>
 * 
 * @author YaoJun
 * @since 2026-01-09
 */
public class LeakyBucketRateLimiter {

    /**
     * 桶的容量（最大水量）
     */
    private final double capacity;

    /**
     * 漏出速率：每秒漏出多少单位水
     */
    private final double leakRatePerSecond;

    /**
     * 桶的状态（水量 + 上次更新时间）
     * 使用 AtomicReference 保证原子性更新
     */
    private final AtomicReference<BucketState> state;

    /**
     * 桶的状态 - 不可变对象
     */
    private static class BucketState {
        /** 当前水量 */
        final double water;
        /** 上次更新时间（纳秒） */
        final long lastUpdateTime;

        BucketState(double water, long lastUpdateTime) {
            this.water = water;
            this.lastUpdateTime = lastUpdateTime;
        }
    }

    /**
     * 构造漏桶限流器
     *
     * @param capacity          桶容量（最大水量），如 100 表示最多缓存100个请求
     * @param leakRatePerSecond 漏出速率（每秒处理的请求数），如 10 表示每秒处理10个
     */
    public LeakyBucketRateLimiter(double capacity, double leakRatePerSecond) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("容量必须为正数，当前值: " + capacity);
        }
        if (leakRatePerSecond <= 0) {
            throw new IllegalArgumentException("漏出速率必须为正数，当前值: " + leakRatePerSecond);
        }

        this.capacity = capacity;
        this.leakRatePerSecond = leakRatePerSecond;
        // 初始状态：桶是空的
        this.state = new AtomicReference<>(new BucketState(0, System.nanoTime()));
    }

    /**
     * 尝试获取通行许可（非阻塞）
     * 
     * <p>
     * 核心逻辑：
     * <ol>
     * <li>计算从上次更新到现在漏掉的水量</li>
     * <li>更新当前水量 = 旧水量 - 漏掉的水量</li>
     * <li>判断：当前水量 + 1 <= 容量 → 成功，否则失败</li>
     * </ol>
     *
     * @return true = 成功（请求入桶），false = 失败（桶满拒绝）
     */
    public boolean tryAcquire() {
        return tryAcquire(1);
    }

    /**
     * 尝试获取多个许可
     *
     * @param permits 需要的许可数量（相当于入桶的水量）
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

            // 2. 懒更新：计算当前实际水量
            double currentWater = refreshWater(currentState, now);

            // 3. 判断加入新请求后是否会溢出
            double newWater = currentWater + permits;
            if (newWater > capacity) {
                return false; // 桶满，拒绝请求
            }

            // 4. CAS 更新状态
            BucketState newState = new BucketState(newWater, now);
            if (state.compareAndSet(currentState, newState)) {
                return true; // 成功入桶
            }

            // CAS 失败，说明有其他线程并发修改，重试
            Thread.onSpinWait();
        }
    }

    /**
     * 计算当前实际水量（懒更新）
     *
     * @param currentState 当前状态
     * @param now          当前时间（纳秒）
     * @return 扣除漏出后的实际水量
     */
    private double refreshWater(BucketState currentState, long now) {
        // 计算经过的时间（秒）
        double elapsedSeconds = (now - currentState.lastUpdateTime) / 1_000_000_000.0;
        // 计算漏掉的水量
        double leakedWater = elapsedSeconds * leakRatePerSecond;
        // 返回当前水量（不能为负数）
        return Math.max(0, currentState.water - leakedWater);
    }

    /**
     * 获取当前桶中的水量
     *
     * @return 当前水量（已考虑漏出）
     */
    public double getCurrentWater() {
        return refreshWater(state.get(), System.nanoTime());
    }

    /**
     * 获取桶的使用率
     *
     * @return 使用率（0.0 ~ 1.0）
     */
    public double getUsageRate() {
        return getCurrentWater() / capacity;
    }

    /**
     * 获取桶的剩余容量
     *
     * @return 剩余可入桶的水量
     */
    public double getRemainingCapacity() {
        return capacity - getCurrentWater();
    }

    /**
     * 获取桶容量
     */
    public double getCapacity() {
        return capacity;
    }

    /**
     * 获取漏出速率
     */
    public double getLeakRatePerSecond() {
        return leakRatePerSecond;
    }

    /**
     * 重置限流器（清空桶）
     */
    public void reset() {
        state.set(new BucketState(0, System.nanoTime()));
    }

    @Override
    public String toString() {
        return String.format(
                "LeakyBucket{容量=%.0f, 当前水量=%.1f, 使用率=%.1f%%, 漏出速率=%.0f/秒}",
                capacity, getCurrentWater(), getUsageRate() * 100, leakRatePerSecond);
    }
}
