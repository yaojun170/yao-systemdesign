package cn.yj.sd.distributelock.db;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * 基于乐观锁(Version)的分布式锁实现
 * 
 * <h2>实现原理</h2>
 * <p>
 * 利用版本号机制实现无锁竞争：
 * <ul>
 * <li>每次更新时检查版本号是否与预期一致</li>
 * <li>更新成功则版本号+1</li>
 * <li>更新失败则说明有并发修改，需要重试或放弃</li>
 * </ul>
 * 
 * <h2>核心SQL</h2>
 * 
 * <pre>
 * -- 查询当前锁状态和版本号
 * SELECT * FROM distributed_lock WHERE lock_name = ?;
 * 
 * -- 乐观锁更新（CAS操作）
 * UPDATE distributed_lock 
 * SET lock_value = ?, expire_time = ?, version = version + 1 
 * WHERE lock_name = ? AND version = ?;
 * </pre>
 * 
 * <h2>使用场景</h2>
 * <ul>
 * <li>读多写少的场景</li>
 * <li>并发冲突概率较低的场景</li>
 * <li>对性能要求较高的场景</li>
 * </ul>
 * 
 * <h2>优点</h2>
 * <ul>
 * <li>无锁设计，性能较高</li>
 * <li>不会产生死锁</li>
 * <li>实现相对简单</li>
 * </ul>
 * 
 * <h2>缺点</h2>
 * <ul>
 * <li>高并发下冲突率高，需要频繁重试</li>
 * <li>ABA问题（本实现通过版本号解决）</li>
 * <li>不适合写操作频繁的场景</li>
 * </ul>
 * 
 * @author yaojun
 */
public class OptimisticDistributedLock extends AbstractDistributedLock {

    /** 最大重试次数 */
    private final int maxRetries;

    /** 重试间隔（毫秒） */
    private final long retryIntervalMs;

    /** 当前持有锁时的版本号 */
    private volatile int currentVersion = -1;

    /** 是否持有锁 */
    private volatile boolean locked = false;

    /** 默认最大重试次数 */
    private static final int DEFAULT_MAX_RETRIES = 3;

    /** 默认重试间隔 */
    private static final long DEFAULT_RETRY_INTERVAL_MS = 50;

    /**
     * 构造函数
     * 
     * @param lockName       锁名称
     * @param lockRepository 数据访问层
     */
    public OptimisticDistributedLock(String lockName, LockRepository lockRepository) {
        this(lockName, DEFAULT_EXPIRE_SECONDS, lockRepository,
                DEFAULT_MAX_RETRIES, DEFAULT_RETRY_INTERVAL_MS);
    }

    /**
     * 构造函数
     * 
     * @param lockName       锁名称
     * @param expireSeconds  过期时间（秒）
     * @param lockRepository 数据访问层
     */
    public OptimisticDistributedLock(String lockName, long expireSeconds,
            LockRepository lockRepository) {
        this(lockName, expireSeconds, lockRepository,
                DEFAULT_MAX_RETRIES, DEFAULT_RETRY_INTERVAL_MS);
    }

    /**
     * 构造函数（完整参数）
     * 
     * @param lockName        锁名称
     * @param expireSeconds   过期时间（秒）
     * @param lockRepository  数据访问层
     * @param maxRetries      最大重试次数
     * @param retryIntervalMs 重试间隔（毫秒）
     */
    public OptimisticDistributedLock(String lockName, long expireSeconds,
            LockRepository lockRepository,
            int maxRetries, long retryIntervalMs) {
        super(lockName, expireSeconds, lockRepository);
        this.maxRetries = maxRetries;
        this.retryIntervalMs = retryIntervalMs;
    }

    /**
     * 尝试获取锁（带重试）
     * 
     * 获取锁流程：
     * 1. 查询当前锁状态
     * 2. 如果锁不存在，尝试插入新锁
     * 3. 如果锁存在且已过期或是当前持有者，尝试CAS更新
     * 4. 更新失败则重试（最多maxRetries次）
     * 
     * @return true-获取成功，false-获取失败
     */
    @Override
    public boolean tryLock() throws LockException {
        for (int retry = 0; retry <= maxRetries; retry++) {
            if (doTryLock()) {
                return true;
            }

            // 最后一次重试失败后不再等待
            if (retry < maxRetries) {
                try {
                    Thread.sleep(retryIntervalMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new LockException(LockException.ErrorType.ACQUIRE_FAILED,
                            "Lock acquisition interrupted");
                }
            }
        }
        return false;
    }

    /**
     * 单次尝试获取锁
     */
    private boolean doTryLock() {
        Optional<LockInfo> existingLock = lockRepository.findByLockName(lockName);

        if (!existingLock.isPresent()) {
            // 锁不存在，尝试插入新锁
            return tryInsertLock();
        }

        LockInfo lockInfo = existingLock.get();

        // 检查是否是同一持有者（重入）
        if (lockInfo.isHeldBy(lockValue)) {
            if (!lockInfo.isExpired()) {
                // 重入成功
                this.currentVersion = lockInfo.getVersion();
                this.locked = true;
                return lockRepository.incrementReentrantCount(lockName, lockValue);
            }
        }

        // 检查锁是否已过期
        if (!lockInfo.isExpired()) {
            // 锁被其他持有者持有且未过期
            return false;
        }

        // 锁已过期，尝试CAS更新
        return tryCASUpdate(lockInfo.getVersion());
    }

    /**
     * 尝试插入新锁
     */
    private boolean tryInsertLock() {
        LockInfo newLock = new LockInfo(
                lockName,
                lockValue,
                LocalDateTime.now().plusSeconds(expireSeconds));

        boolean inserted = lockRepository.insertLock(newLock);
        if (inserted) {
            this.currentVersion = 0;
            this.locked = true;
        }
        return inserted;
    }

    /**
     * 尝试CAS更新锁
     * 
     * @param expectedVersion 期望的版本号
     * @return true-更新成功
     */
    private boolean tryCASUpdate(int expectedVersion) {
        LocalDateTime expireTime = LocalDateTime.now().plusSeconds(expireSeconds);

        boolean updated = lockRepository.updateWithOptimisticLock(
                lockName, lockValue, expectedVersion, expireTime);

        if (updated) {
            this.currentVersion = expectedVersion + 1;
            this.locked = true;
        }
        return updated;
    }

    /**
     * 带超时的尝试获取锁
     * 在指定时间内不断重试
     */
    @Override
    public boolean tryLock(long timeout, TimeUnit unit) throws LockException {
        long startTime = System.currentTimeMillis();
        long timeoutMs = unit.toMillis(timeout);

        while (true) {
            if (doTryLock()) {
                return true;
            }

            if (System.currentTimeMillis() - startTime >= timeoutMs) {
                return false;
            }

            try {
                Thread.sleep(retryIntervalMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new LockException(LockException.ErrorType.ACQUIRE_FAILED,
                        "Lock acquisition interrupted");
            }
        }
    }

    /**
     * 释放锁
     * 
     * 乐观锁释放比较简单：
     * 1. 验证是否是锁的持有者
     * 2. 减少重入计数或删除锁记录
     */
    @Override
    public void unlock() throws LockException {
        if (!locked) {
            throw new LockException(LockException.ErrorType.LOCK_NOT_EXISTS,
                    "Lock is not held: " + lockName);
        }

        Optional<LockInfo> existingLock = lockRepository.findByLockName(lockName);

        if (!existingLock.isPresent()) {
            this.locked = false;
            this.currentVersion = -1;
            throw new LockException(LockException.ErrorType.LOCK_NOT_EXISTS,
                    "Lock does not exist: " + lockName);
        }

        LockInfo lockInfo = existingLock.get();

        // 验证是否是锁的持有者
        if (!lockInfo.isHeldBy(lockValue)) {
            throw new LockException(LockException.ErrorType.NOT_LOCK_OWNER,
                    "Current holder is not the owner of lock: " + lockName);
        }

        // 减少重入计数
        if (lockInfo.getReentrantCount() != null && lockInfo.getReentrantCount() > 1) {
            int newCount = lockRepository.decrementReentrantCount(lockName, lockValue);
            if (newCount < 0) {
                throw new LockException(LockException.ErrorType.RELEASE_FAILED,
                        "Failed to decrement reentrant count for lock: " + lockName);
            }
        } else {
            // 重入计数为1，直接删除锁
            boolean deleted = lockRepository.deleteLock(lockName, lockValue);
            if (!deleted) {
                throw new LockException(LockException.ErrorType.RELEASE_FAILED,
                        "Failed to release lock: " + lockName);
            }
            this.locked = false;
            this.currentVersion = -1;
        }
    }

    @Override
    public boolean isLocked() {
        return locked;
    }

    @Override
    public boolean isHeldByCurrentHolder() {
        return locked && lockRepository.findByLockName(lockName)
                .map(info -> info.isHeldBy(lockValue) && !info.isExpired())
                .orElse(false);
    }

    /**
     * 使用CAS进行锁续期
     * 
     * @param additionalSeconds 额外增加的秒数
     * @return true-续期成功
     */
    public boolean renew(long additionalSeconds) {
        if (!locked) {
            return false;
        }

        Optional<LockInfo> existingLock = lockRepository.findByLockName(lockName);
        if (!existingLock.isPresent()) {
            return false;
        }

        LockInfo lockInfo = existingLock.get();
        if (!lockInfo.isHeldBy(lockValue)) {
            return false;
        }

        LocalDateTime newExpireTime = LocalDateTime.now().plusSeconds(additionalSeconds);
        boolean renewed = lockRepository.updateWithOptimisticLock(
                lockName, lockValue, lockInfo.getVersion(), newExpireTime);

        if (renewed) {
            this.currentVersion = lockInfo.getVersion() + 1;
        }
        return renewed;
    }

    /**
     * 获取当前版本号
     */
    public int getCurrentVersion() {
        return currentVersion;
    }
}
