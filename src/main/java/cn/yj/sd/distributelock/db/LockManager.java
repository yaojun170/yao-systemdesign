package cn.yj.sd.distributelock.db;

import javax.sql.DataSource;
import java.util.Map;
import java.util.concurrent.*;

/**
 * 分布式锁管理器
 * 提供锁的创建、续期、过期清理等功能
 * 
 * <h2>功能特性</h2>
 * <ul>
 * <li>统一创建各类型分布式锁</li>
 * <li>自动续期（Watchdog机制）</li>
 * <li>定时清理过期锁</li>
 * <li>锁缓存管理</li>
 * </ul>
 * 
 * @author yaojun
 */
public class LockManager {

    /** 数据访问层 */
    private final LockRepository lockRepository;

    /** 锁缓存 */
    private final Map<String, DistributedLock> lockCache;

    /** 续期任务调度器 */
    private final ScheduledExecutorService renewScheduler;

    /** 清理任务调度器 */
    private final ScheduledExecutorService cleanScheduler;

    /** 续期任务Map */
    private final Map<String, ScheduledFuture<?>> renewTasks;

    /** 默认续期间隔（锁过期时间的1/3） */
    private static final double RENEW_INTERVAL_RATIO = 1.0 / 3;

    /** 默认清理间隔（秒） */
    private static final long DEFAULT_CLEAN_INTERVAL_SECONDS = 60;

    /** 是否已关闭 */
    private volatile boolean shutdown = false;

    /**
     * 构造函数
     * 
     * @param dataSource 数据源
     */
    public LockManager(DataSource dataSource) {
        this(new LockRepository(dataSource));
    }

    /**
     * 构造函数
     * 
     * @param lockRepository 数据访问层
     */
    public LockManager(LockRepository lockRepository) {
        this.lockRepository = lockRepository;
        this.lockCache = new ConcurrentHashMap<>();
        this.renewTasks = new ConcurrentHashMap<>();

        // 创建续期调度器
        this.renewScheduler = Executors.newScheduledThreadPool(2, r -> {
            Thread t = new Thread(r, "lock-renew-scheduler");
            t.setDaemon(true);
            return t;
        });

        // 创建清理调度器
        this.cleanScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "lock-clean-scheduler");
            t.setDaemon(true);
            return t;
        });

        // 启动定时清理任务
        startCleanTask();
    }

    // ==================== 锁创建工厂方法 ====================

    /**
     * 创建唯一索引锁
     * 
     * @param lockName 锁名称
     * @return 唯一索引锁实例
     */
    public UniqueKeyDistributedLock createUniqueKeyLock(String lockName) {
        return new UniqueKeyDistributedLock(lockName, lockRepository);
    }

    /**
     * 创建唯一索引锁（指定过期时间）
     * 
     * @param lockName      锁名称
     * @param expireSeconds 过期时间（秒）
     * @return 唯一索引锁实例
     */
    public UniqueKeyDistributedLock createUniqueKeyLock(String lockName, long expireSeconds) {
        return new UniqueKeyDistributedLock(lockName, expireSeconds, lockRepository);
    }

    /**
     * 创建悲观锁
     * 
     * @param lockName 锁名称
     * @return 悲观锁实例
     */
    public PessimisticDistributedLock createPessimisticLock(String lockName) {
        return new PessimisticDistributedLock(lockName, lockRepository);
    }

    /**
     * 创建悲观锁（指定过期时间）
     * 
     * @param lockName      锁名称
     * @param expireSeconds 过期时间（秒）
     * @return 悲观锁实例
     */
    public PessimisticDistributedLock createPessimisticLock(String lockName, long expireSeconds) {
        return new PessimisticDistributedLock(lockName, expireSeconds, lockRepository);
    }

    /**
     * 创建乐观锁
     * 
     * @param lockName 锁名称
     * @return 乐观锁实例
     */
    public OptimisticDistributedLock createOptimisticLock(String lockName) {
        return new OptimisticDistributedLock(lockName, lockRepository);
    }

    /**
     * 创建乐观锁（指定过期时间）
     * 
     * @param lockName      锁名称
     * @param expireSeconds 过期时间（秒）
     * @return 乐观锁实例
     */
    public OptimisticDistributedLock createOptimisticLock(String lockName, long expireSeconds) {
        return new OptimisticDistributedLock(lockName, expireSeconds, lockRepository);
    }

    // ==================== 锁续期（Watchdog机制） ====================

    /**
     * 为唯一索引锁启动自动续期
     * 
     * @param lock 锁实例
     * @return 是否启动成功
     */
    public boolean startAutoRenew(UniqueKeyDistributedLock lock) {
        if (shutdown) {
            return false;
        }

        String lockName = lock.getLockName();

        // 避免重复启动
        if (renewTasks.containsKey(lockName)) {
            return true;
        }

        // 计算续期间隔（锁过期时间的1/3）
        long intervalSeconds = (long) (lock.getExpireSeconds() * RENEW_INTERVAL_RATIO);
        if (intervalSeconds < 1) {
            intervalSeconds = 1;
        }

        // 创建续期任务
        ScheduledFuture<?> future = renewScheduler.scheduleAtFixedRate(() -> {
            try {
                if (lock.isHeldByCurrentHolder()) {
                    boolean renewed = lock.renew(lock.getExpireSeconds());
                    if (!renewed) {
                        // 续期失败，停止续期任务
                        stopAutoRenew(lockName);
                    }
                } else {
                    // 不再持有锁，停止续期任务
                    stopAutoRenew(lockName);
                }
            } catch (Exception e) {
                // 续期异常，记录日志并继续
                System.err.println("Lock renew failed for " + lockName + ": " + e.getMessage());
            }
        }, intervalSeconds, intervalSeconds, TimeUnit.SECONDS);

        renewTasks.put(lockName, future);
        return true;
    }

    /**
     * 为乐观锁启动自动续期
     * 
     * @param lock 锁实例
     * @return 是否启动成功
     */
    public boolean startAutoRenew(OptimisticDistributedLock lock) {
        if (shutdown) {
            return false;
        }

        String lockName = lock.getLockName();

        if (renewTasks.containsKey(lockName)) {
            return true;
        }

        long intervalSeconds = (long) (lock.getExpireSeconds() * RENEW_INTERVAL_RATIO);
        if (intervalSeconds < 1) {
            intervalSeconds = 1;
        }

        ScheduledFuture<?> future = renewScheduler.scheduleAtFixedRate(() -> {
            try {
                if (lock.isHeldByCurrentHolder()) {
                    boolean renewed = lock.renew(lock.getExpireSeconds());
                    if (!renewed) {
                        stopAutoRenew(lockName);
                    }
                } else {
                    stopAutoRenew(lockName);
                }
            } catch (Exception e) {
                System.err.println("Lock renew failed for " + lockName + ": " + e.getMessage());
            }
        }, intervalSeconds, intervalSeconds, TimeUnit.SECONDS);

        renewTasks.put(lockName, future);
        return true;
    }

    /**
     * 停止自动续期
     * 
     * @param lockName 锁名称
     */
    public void stopAutoRenew(String lockName) {
        ScheduledFuture<?> future = renewTasks.remove(lockName);
        if (future != null) {
            future.cancel(false);
        }
    }

    // ==================== 过期锁清理 ====================

    /**
     * 启动定时清理任务
     */
    private void startCleanTask() {
        cleanScheduler.scheduleAtFixedRate(() -> {
            try {
                int cleaned = lockRepository.cleanExpiredLocks();
                if (cleaned > 0) {
                    System.out.println("Cleaned " + cleaned + " expired locks");
                }
            } catch (Exception e) {
                System.err.println("Failed to clean expired locks: " + e.getMessage());
            }
        }, DEFAULT_CLEAN_INTERVAL_SECONDS, DEFAULT_CLEAN_INTERVAL_SECONDS, TimeUnit.SECONDS);
    }

    /**
     * 手动清理过期锁
     * 
     * @return 清理的锁数量
     */
    public int cleanExpiredLocks() {
        return lockRepository.cleanExpiredLocks();
    }

    // ==================== 带自动续期的锁操作 ====================

    /**
     * 获取锁并执行操作（自动续期）
     * 
     * @param lockName 锁名称
     * @param action   要执行的操作
     * @param <T>      返回类型
     * @return 操作结果
     */
    public <T> T executeWithLock(String lockName, LockAction<T> action) throws LockException {
        return executeWithLock(lockName, 30, action);
    }

    /**
     * 获取锁并执行操作（自动续期）
     * 
     * @param lockName      锁名称
     * @param expireSeconds 过期时间（秒）
     * @param action        要执行的操作
     * @param <T>           返回类型
     * @return 操作结果
     */
    public <T> T executeWithLock(String lockName, long expireSeconds,
            LockAction<T> action) throws LockException {
        UniqueKeyDistributedLock lock = createUniqueKeyLock(lockName, expireSeconds);

        try {
            if (!lock.tryLock()) {
                throw new LockException(LockException.ErrorType.ACQUIRE_FAILED,
                        "Failed to acquire lock: " + lockName);
            }

            // 启动自动续期
            startAutoRenew(lock);

            return action.execute();

        } finally {
            stopAutoRenew(lockName);
            if (lock.isHeldByCurrentHolder()) {
                lock.unlock();
            }
        }
    }

    /**
     * 锁保护下的操作接口
     */
    @FunctionalInterface
    public interface LockAction<T> {
        T execute() throws LockException;
    }

    // ==================== 资源管理 ====================

    /**
     * 关闭锁管理器
     * 释放所有资源
     */
    public void shutdown() {
        shutdown = true;

        // 取消所有续期任务
        for (ScheduledFuture<?> future : renewTasks.values()) {
            future.cancel(true);
        }
        renewTasks.clear();

        // 关闭调度器
        renewScheduler.shutdown();
        cleanScheduler.shutdown();

        try {
            if (!renewScheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                renewScheduler.shutdownNow();
            }
            if (!cleanScheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                cleanScheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            renewScheduler.shutdownNow();
            cleanScheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }

        // 清理锁缓存
        lockCache.clear();
    }

    /**
     * 获取数据访问层
     */
    public LockRepository getLockRepository() {
        return lockRepository;
    }
}
