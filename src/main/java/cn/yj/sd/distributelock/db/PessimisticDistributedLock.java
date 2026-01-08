package cn.yj.sd.distributelock.db;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.Optional;

/**
 * 基于悲观锁(FOR UPDATE)的分布式锁实现
 * 
 * <h2>实现原理</h2>
 * <p>
 * 利用数据库的行级锁机制：
 * <ul>
 * <li>使用 SELECT ... FOR UPDATE 获取行锁</li>
 * <li>在事务提交或回滚时自动释放锁</li>
 * <li>其他事务尝试获取同一行锁时会被阻塞</li>
 * </ul>
 * 
 * <h2>核心SQL</h2>
 * 
 * <pre>
 * -- 开启事务
 * BEGIN;
 * 
 * -- 获取锁（行锁）
 * SELECT * FROM distributed_lock 
 * WHERE lock_name = ? FOR UPDATE;
 * 
 * -- 更新锁持有者
 * UPDATE distributed_lock 
 * SET lock_value = ?, expire_time = ? 
 * WHERE lock_name = ?;
 * 
 * -- 释放锁
 * COMMIT; -- 或 ROLLBACK
 * </pre>
 * 
 * <h2>使用场景</h2>
 * <ul>
 * <li>需要在数据库事务中保持锁的场景</li>
 * <li>锁持有时间较短的场景</li>
 * <li>对可靠性要求极高的场景</li>
 * </ul>
 * 
 * <h2>优点</h2>
 * <ul>
 * <li>可靠性高，由数据库保证锁的原子性</li>
 * <li>锁释放有保障（事务结束自动释放）</li>
 * <li>避免了死锁风险（数据库自动检测并处理）</li>
 * </ul>
 * 
 * <h2>缺点</h2>
 * <ul>
 * <li>锁粒度是数据库连接级别，占用连接资源</li>
 * <li>性能较低，依赖数据库行锁</li>
 * <li>长时间持有锁会导致其他请求阻塞</li>
 * </ul>
 * 
 * <h2>注意事项</h2>
 * <ul>
 * <li>必须在事务中使用</li>
 * <li>确保事务隔离级别至少为 READ COMMITTED</li>
 * <li>要设置合理的锁等待超时时间</li>
 * </ul>
 * 
 * @author yaojun
 */
public class PessimisticDistributedLock extends AbstractDistributedLock {

    /** 当前持有的数据库连接 */
    private volatile Connection lockedConnection;

    /** 锁是否已被持有 */
    private volatile boolean locked = false;

    /**
     * 构造函数
     * 
     * @param lockName       锁名称
     * @param lockRepository 数据访问层
     */
    public PessimisticDistributedLock(String lockName, LockRepository lockRepository) {
        super(lockName, lockRepository);
    }

    /**
     * 构造函数
     * 
     * @param lockName       锁名称
     * @param expireSeconds  过期时间（秒）
     * @param lockRepository 数据访问层
     */
    public PessimisticDistributedLock(String lockName, long expireSeconds,
            LockRepository lockRepository) {
        super(lockName, expireSeconds, lockRepository);
    }

    /**
     * 尝试获取锁
     * 
     * 获取锁流程：
     * 1. 获取数据库连接，开启事务
     * 2. 执行 SELECT ... FOR UPDATE 获取行锁
     * 3. 如果锁记录不存在，创建新记录
     * 4. 检查锁是否过期或是否可重入
     * 5. 更新锁的持有者信息
     * 
     * 注意：获取锁后，必须调用unlock()释放锁和连接
     * 
     * @return true-获取成功，false-获取失败
     */
    @Override
    public boolean tryLock() throws LockException {
        if (locked) {
            // 已经持有锁，检查是否是重入
            if (isHeldByCurrentHolder()) {
                return lockRepository.incrementReentrantCount(lockName, lockValue);
            }
            return false;
        }

        Connection connection = null;
        try {
            // 获取连接并开启事务
            connection = lockRepository.getConnection();
            connection.setAutoCommit(false);

            // 设置锁等待超时（避免无限等待）
            java.sql.Statement stmt = connection.createStatement();
            try {
                stmt.execute("SET innodb_lock_wait_timeout = 3");
            } finally {
                stmt.close();
            }

            // 尝试获取行锁
            Optional<LockInfo> existingLock = lockRepository.findByLockNameForUpdate(lockName, connection);

            if (existingLock.isPresent()) {
                LockInfo lockInfo = existingLock.get();

                // 检查是否是同一持有者（重入）
                if (lockInfo.isHeldBy(lockValue) && !lockInfo.isExpired()) {
                    lockRepository.incrementReentrantCount(lockName, lockValue);
                    this.lockedConnection = connection;
                    this.locked = true;
                    return true;
                }

                // 检查锁是否已过期
                if (!lockInfo.isExpired()) {
                    // 锁被其他持有者持有且未过期
                    connection.rollback();
                    connection.close();
                    return false;
                }

                // 锁已过期，更新持有者
                LocalDateTime expireTime = LocalDateTime.now().plusSeconds(expireSeconds);
                boolean updated = lockRepository.updateLockHolder(lockName, lockValue,
                        expireTime, connection);

                if (updated) {
                    this.lockedConnection = connection;
                    this.locked = true;
                    return true;
                }

            } else {
                // 锁记录不存在，需要先创建（在事务外创建）
                connection.rollback();
                connection.close();

                // 尝试插入新锁记录
                LockInfo newLock = new LockInfo(
                        lockName,
                        lockValue,
                        LocalDateTime.now().plusSeconds(expireSeconds));

                if (lockRepository.insertLock(newLock)) {
                    // 插入成功后，重新获取FOR UPDATE锁
                    return tryLockForUpdate();
                }

                return false;
            }

            connection.rollback();
            connection.close();
            return false;

        } catch (SQLException e) {
            if (connection != null) {
                try {
                    connection.rollback();
                    connection.close();
                } catch (SQLException ex) {
                    // ignore
                }
            }
            throw new LockException(LockException.ErrorType.ACQUIRE_FAILED,
                    "Failed to acquire pessimistic lock: " + lockName, e);
        }
    }

    /**
     * 获取FOR UPDATE锁
     */
    private boolean tryLockForUpdate() throws LockException {
        Connection connection = null;
        try {
            connection = lockRepository.getConnection();
            connection.setAutoCommit(false);

            Optional<LockInfo> lockInfo = lockRepository.findByLockNameForUpdate(lockName, connection);

            if (lockInfo.isPresent() && lockInfo.get().isHeldBy(lockValue)) {
                this.lockedConnection = connection;
                this.locked = true;
                return true;
            }

            connection.rollback();
            connection.close();
            return false;

        } catch (SQLException e) {
            if (connection != null) {
                try {
                    connection.rollback();
                    connection.close();
                } catch (SQLException ex) {
                    // ignore
                }
            }
            throw new LockException(LockException.ErrorType.ACQUIRE_FAILED,
                    "Failed to acquire FOR UPDATE lock: " + lockName, e);
        }
    }

    /**
     * 释放锁
     * 
     * 释放锁流程：
     * 1. 检查是否持有锁
     * 2. 减少重入计数
     * 3. 如果重入计数为0，提交或回滚事务
     * 4. 关闭数据库连接
     */
    @Override
    public void unlock() throws LockException {
        if (!locked) {
            throw new LockException(LockException.ErrorType.LOCK_NOT_EXISTS,
                    "Lock is not held: " + lockName);
        }

        try {
            // 检查重入计数
            Optional<LockInfo> lockInfo = lockRepository.findByLockName(lockName);

            if (lockInfo.isPresent() && lockInfo.get().getReentrantCount() != null
                    && lockInfo.get().getReentrantCount() > 1) {
                // 只减少重入计数，不释放锁
                lockRepository.decrementReentrantCount(lockName, lockValue);
                return;
            }

            // 删除锁记录
            lockRepository.deleteLock(lockName, lockValue);

            // 提交事务并关闭连接
            if (lockedConnection != null) {
                lockedConnection.commit();
                lockedConnection.close();
            }

        } catch (SQLException e) {
            if (lockedConnection != null) {
                try {
                    lockedConnection.rollback();
                    lockedConnection.close();
                } catch (SQLException ex) {
                    // ignore
                }
            }
            throw new LockException(LockException.ErrorType.RELEASE_FAILED,
                    "Failed to release pessimistic lock: " + lockName, e);
        } finally {
            this.locked = false;
            this.lockedConnection = null;
        }
    }

    @Override
    public boolean isLocked() {
        return locked;
    }

    @Override
    public boolean isHeldByCurrentHolder() {
        return locked && lockRepository.findByLockName(lockName)
                .map(info -> info.isHeldBy(lockValue))
                .orElse(false);
    }

    /**
     * 在锁保护下执行操作
     * 自动处理锁的获取和释放
     * 
     * @param action 要执行的操作
     * @param <T>    返回类型
     * @return 操作结果
     */
    public <T> T executeWithLock(LockAction<T> action) throws LockException {
        try {
            if (!tryLock()) {
                throw new LockException(LockException.ErrorType.ACQUIRE_FAILED,
                        "Failed to acquire lock: " + lockName);
            }
            return action.execute();
        } finally {
            if (locked) {
                unlock();
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
}
