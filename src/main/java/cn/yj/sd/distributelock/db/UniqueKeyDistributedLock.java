package cn.yj.sd.distributelock.db;

import java.time.LocalDateTime;
import java.util.Optional;

/**
 * 基于唯一索引的分布式锁实现
 * 
 * <h2>实现原理</h2>
 * <p>
 * 利用数据库唯一索引的特性：
 * <ul>
 * <li>获取锁：向数据库插入一条记录，插入成功即获取锁</li>
 * <li>释放锁：删除该条记录</li>
 * <li>锁的唯一性由数据库唯一索引保证</li>
 * </ul>
 * 
 * <h2>核心SQL</h2>
 * 
 * <pre>
 * -- 获取锁（插入成功即获取）
 * INSERT INTO distributed_lock (lock_name, lock_value, expire_time) 
 * VALUES (?, ?, ?);
 * 
 * -- 释放锁
 * DELETE FROM distributed_lock 
 * WHERE lock_name = ? AND lock_value = ?;
 * </pre>
 * 
 * <h2>优点</h2>
 * <ul>
 * <li>实现简单直观</li>
 * <li>可靠性高，基于数据库ACID特性</li>
 * </ul>
 * 
 * <h2>缺点</h2>
 * <ul>
 * <li>数据库性能是瓶颈</li>
 * <li>不支持锁重入（本实现已增强支持）</li>
 * <li>获取锁失败需要轮询重试</li>
 * </ul>
 * 
 * @author yaojun
 */
public class UniqueKeyDistributedLock extends AbstractDistributedLock {

    /**
     * 构造函数
     * 
     * @param lockName       锁名称
     * @param lockRepository 数据访问层
     */
    public UniqueKeyDistributedLock(String lockName, LockRepository lockRepository) {
        super(lockName, lockRepository);
    }

    /**
     * 构造函数
     * 
     * @param lockName       锁名称
     * @param expireSeconds  过期时间（秒）
     * @param lockRepository 数据访问层
     */
    public UniqueKeyDistributedLock(String lockName, long expireSeconds,
            LockRepository lockRepository) {
        super(lockName, expireSeconds, lockRepository);
    }

    /**
     * 构造函数（完整参数）
     * 
     * @param lockName       锁名称
     * @param lockValue      锁值
     * @param expireSeconds  过期时间（秒）
     * @param lockRepository 数据访问层
     */
    public UniqueKeyDistributedLock(String lockName, String lockValue, long expireSeconds,
            LockRepository lockRepository) {
        super(lockName, lockValue, expireSeconds, lockRepository);
    }

    /**
     * 尝试获取锁
     * 
     * 获取锁流程：
     * 1. 检查锁是否存在
     * - 不存在：尝试插入新锁
     * - 存在且是当前持有者：支持重入，增加重入计数
     * - 存在且已过期：删除过期锁，重新尝试插入
     * - 存在且未过期：获取失败
     * 
     * @return true-获取成功，false-获取失败
     */
    @Override
    public boolean tryLock() throws LockException {
        // 先检查是否是重入
        Optional<LockInfo> existingLock = lockRepository.findByLockName(lockName);

        if (existingLock.isPresent()) {
            LockInfo lockInfo = existingLock.get();

            // 检查是否是同一持有者（重入）
            if (lockInfo.isHeldBy(lockValue)) {
                if (!lockInfo.isExpired()) {
                    // 重入成功，增加重入计数
                    return lockRepository.incrementReentrantCount(lockName, lockValue);
                }
            }

            // 检查锁是否已过期
            if (lockInfo.isExpired()) {
                // 尝试删除过期锁
                lockRepository.deleteExpiredLock(lockName);
            } else {
                // 锁被其他持有者持有且未过期
                return false;
            }
        }

        // 尝试插入新锁
        LockInfo newLock = new LockInfo(
                lockName,
                lockValue,
                LocalDateTime.now().plusSeconds(expireSeconds));

        return lockRepository.insertLock(newLock);
    }

    /**
     * 释放锁
     * 
     * 释放锁流程：
     * 1. 检查锁是否存在且由当前持有者持有
     * 2. 减少重入计数
     * 3. 如果重入计数为0，删除锁记录
     */
    @Override
    public void unlock() throws LockException {
        Optional<LockInfo> existingLock = lockRepository.findByLockName(lockName);

        if (!existingLock.isPresent()) {
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
        }
    }

    /**
     * 锁续期
     * 延长锁的过期时间
     * 
     * @param additionalSeconds 额外增加的秒数
     * @return true-续期成功
     */
    public boolean renew(long additionalSeconds) {
        LocalDateTime newExpireTime = LocalDateTime.now().plusSeconds(additionalSeconds);
        return lockRepository.renewLock(lockName, lockValue, newExpireTime);
    }
}
