package cn.yj.sd.distributelock.redis;

import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.params.SetParams;

import java.util.Collections;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 基于Redis的分布式锁实现
 * 
 * 核心特性：
 * 1. 原子性加锁：使用 SET key value NX PX 命令
 * 2. 安全释放：使用 Lua 脚本保证只能释放自己持有的锁
 * 3. 可重入：支持同一线程多次获取锁
 * 4. 自动续期：看门狗机制防止锁过期
 * 5. 防死锁：锁带有过期时间，即使客户端崩溃也能自动释放
 * 
 * @author yaojun
 */
public class RedisDistributedLock implements DistributedLock {

    /** 锁的Redis Key前缀 */
    private static final String LOCK_PREFIX = "distributed_lock:";

    /** 默认锁过期时间（毫秒） */
    private static final long DEFAULT_EXPIRE_MS = 30000;

    /** 看门狗续期间隔 = 过期时间的1/3 */
    private static final long WATCHDOG_INTERVAL_RATIO = 3;

    /** 重试等待间隔（毫秒） */
    private static final long RETRY_INTERVAL_MS = 100;

    /** 释放锁的Lua脚本 - 保证原子性：只有锁值匹配时才删除 */
    private static final String UNLOCK_SCRIPT = "if redis.call('get', KEYS[1]) == ARGV[1] then " +
            "    return redis.call('del', KEYS[1]) " +
            "else " +
            "    return 0 " +
            "end";

    /** 续期锁的Lua脚本 - 保证原子性：只有锁值匹配时才续期 */
    private static final String RENEW_SCRIPT = "if redis.call('get', KEYS[1]) == ARGV[1] then " +
            "    return redis.call('pexpire', KEYS[1], ARGV[2]) " +
            "else " +
            "    return 0 " +
            "end";

    /** 看门狗调度器（全局共享） */
    private static final ScheduledExecutorService WATCHDOG_SCHEDULER = Executors.newScheduledThreadPool(1, r -> {
        Thread t = new Thread(r, "redis-lock-watchdog");
        t.setDaemon(true);
        return t;
    });

    /** 记录每个线程对每把锁的重入次数 */
    private static final Map<String, AtomicInteger> REENTRANT_COUNT = new ConcurrentHashMap<>();

    /** Jedis连接池 */
    private final JedisPool jedisPool;

    /** 锁名称 */
    private final String lockName;

    /** 锁的Redis Key */
    private final String lockKey;

    /** 锁的唯一标识值（区分不同客户端/线程） */
    private final String lockValue;

    /** 锁过期时间（毫秒） */
    private final long expireMs;

    /** 是否启用看门狗自动续期 */
    private final boolean enableWatchdog;

    /** 看门狗任务（用于取消） */
    private volatile ScheduledFuture<?> watchdogFuture;

    /**
     * 创建分布式锁（使用默认配置）
     * 
     * @param lockName 锁名称
     */
    public RedisDistributedLock(String lockName) {
        this(JedisConfig.getJedisPool(), lockName, DEFAULT_EXPIRE_MS, true);
    }

    /**
     * 创建分布式锁
     * 
     * @param lockName 锁名称
     * @param expireMs 过期时间（毫秒）
     */
    public RedisDistributedLock(String lockName, long expireMs) {
        this(JedisConfig.getJedisPool(), lockName, expireMs, true);
    }

    /**
     * 创建分布式锁
     * 
     * @param lockName       锁名称
     * @param expireMs       过期时间（毫秒）
     * @param enableWatchdog 是否启用看门狗自动续期
     */
    public RedisDistributedLock(String lockName, long expireMs, boolean enableWatchdog) {
        this(JedisConfig.getJedisPool(), lockName, expireMs, enableWatchdog);
    }

    /**
     * 创建分布式锁（完整参数）
     * 
     * @param jedisPool      Jedis连接池
     * @param lockName       锁名称
     * @param expireMs       过期时间（毫秒）
     * @param enableWatchdog 是否启用看门狗自动续期
     */
    public RedisDistributedLock(JedisPool jedisPool, String lockName, long expireMs, boolean enableWatchdog) {
        if (jedisPool == null) {
            throw new IllegalArgumentException("JedisPool cannot be null");
        }
        if (lockName == null || lockName.trim().isEmpty()) {
            throw new IllegalArgumentException("Lock name cannot be null or empty");
        }
        if (expireMs <= 0) {
            throw new IllegalArgumentException("Expire time must be positive");
        }

        this.jedisPool = jedisPool;
        this.lockName = lockName;
        this.lockKey = LOCK_PREFIX + lockName;
        // 锁值格式：UUID:线程ID，确保全局唯一
        this.lockValue = UUID.randomUUID().toString() + ":" + Thread.currentThread().getId();
        this.expireMs = expireMs;
        this.enableWatchdog = enableWatchdog;
    }

    @Override
    public boolean tryLock() {
        // 检查是否可重入
        String threadLockKey = getThreadLockKey();
        AtomicInteger count = REENTRANT_COUNT.get(threadLockKey);
        if (count != null && count.get() > 0) {
            // 同一线程重入，直接增加计数
            count.incrementAndGet();
            return true;
        }

        // 尝试获取锁
        try (Jedis jedis = jedisPool.getResource()) {
            // SET key value NX PX expireMs
            // NX: 只有key不存在时才设置
            // PX: 设置过期时间（毫秒）
            SetParams params = SetParams.setParams().nx().px(expireMs);
            String result = jedis.set(lockKey, lockValue, params);

            if ("OK".equals(result)) {
                // 加锁成功，初始化重入计数
                REENTRANT_COUNT.put(threadLockKey, new AtomicInteger(1));

                // 启动看门狗
                if (enableWatchdog) {
                    startWatchdog();
                }
                return true;
            }
            return false;
        } catch (Exception e) {
            throw new RuntimeException("Failed to acquire lock: " + lockName, e);
        }
    }

    @Override
    public boolean tryLock(long waitTime, TimeUnit unit) {
        long waitMs = unit.toMillis(waitTime);
        long startTime = System.currentTimeMillis();

        while (true) {
            // 尝试获取锁
            if (tryLock()) {
                return true;
            }

            // 检查是否超时
            long elapsed = System.currentTimeMillis() - startTime;
            if (elapsed >= waitMs) {
                return false;
            }

            // 等待一段时间后重试
            try {
                long remaining = waitMs - elapsed;
                long sleepTime = Math.min(RETRY_INTERVAL_MS, remaining);
                Thread.sleep(sleepTime);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
    }

    @Override
    public boolean unlock() {
        String threadLockKey = getThreadLockKey();
        AtomicInteger count = REENTRANT_COUNT.get(threadLockKey);

        // 检查当前线程是否持有锁
        if (count == null || count.get() <= 0) {
            return false;
        }

        // 减少重入计数
        int remaining = count.decrementAndGet();
        if (remaining > 0) {
            // 还有重入，不释放锁
            return true;
        }

        // 重入计数归零，释放锁
        REENTRANT_COUNT.remove(threadLockKey);

        // 停止看门狗
        stopWatchdog();

        // 使用Lua脚本原子性释放锁
        try (Jedis jedis = jedisPool.getResource()) {
            Object result = jedis.eval(
                    UNLOCK_SCRIPT,
                    Collections.singletonList(lockKey),
                    Collections.singletonList(lockValue));
            return Long.valueOf(1).equals(result);
        } catch (Exception e) {
            throw new RuntimeException("Failed to release lock: " + lockName, e);
        }
    }

    @Override
    public boolean isHeldByCurrentThread() {
        String threadLockKey = getThreadLockKey();
        AtomicInteger count = REENTRANT_COUNT.get(threadLockKey);
        return count != null && count.get() > 0;
    }

    @Override
    public String getLockName() {
        return lockName;
    }

    /**
     * 获取当前线程的锁标识Key
     */
    private String getThreadLockKey() {
        return lockKey + ":" + lockValue;
    }

    /**
     * 启动看门狗线程，定期续期锁
     */
    private void startWatchdog() {
        long interval = expireMs / WATCHDOG_INTERVAL_RATIO;
        watchdogFuture = WATCHDOG_SCHEDULER.scheduleAtFixedRate(
                this::renewLock,
                interval,
                interval,
                TimeUnit.MILLISECONDS);
    }

    /**
     * 停止看门狗线程
     */
    private void stopWatchdog() {
        if (watchdogFuture != null) {
            watchdogFuture.cancel(false);
            watchdogFuture = null;
        }
    }

    /**
     * 续期锁
     */
    private void renewLock() {
        try (Jedis jedis = jedisPool.getResource()) {
            Object result = jedis.eval(
                    RENEW_SCRIPT,
                    Collections.singletonList(lockKey),
                    java.util.Arrays.asList(lockValue, String.valueOf(expireMs)));
            if (!Long.valueOf(1).equals(result)) {
                // 锁已不属于当前持有者，停止续期
                stopWatchdog();
            }
        } catch (Exception e) {
            // 续期失败，记录日志但不抛出异常
            System.err.println("Failed to renew lock: " + lockName + ", error: " + e.getMessage());
        }
    }

    /**
     * 获取锁的Redis Key
     */
    public String getLockKey() {
        return lockKey;
    }

    /**
     * 获取锁值
     */
    public String getLockValue() {
        return lockValue;
    }
}
