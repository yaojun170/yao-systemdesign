package cn.yj.sd.distributelock.db;

import java.sql.*;
import java.time.LocalDateTime;
import java.util.Optional;
import javax.sql.DataSource;

/**
 * 锁数据访问层
 * 封装所有与数据库交互的操作
 * 
 * @author yaojun
 */
public class LockRepository {

    private final DataSource dataSource;

    public LockRepository(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /**
     * 插入锁记录（用于唯一索引锁）
     * 
     * @param lockInfo 锁信息
     * @return true-插入成功，false-锁已存在
     */
    public boolean insertLock(LockInfo lockInfo) {
        String sql = "INSERT INTO distributed_lock (lock_name, lock_value, expire_time, version, reentrant_count) " +
                "VALUES (?, ?, ?, ?, ?)";

        try (Connection conn = dataSource.getConnection();
                PreparedStatement ps = conn.prepareStatement(sql)) {

            ps.setString(1, lockInfo.getLockName());
            ps.setString(2, lockInfo.getLockValue());
            ps.setTimestamp(3, Timestamp.valueOf(lockInfo.getExpireTime()));
            ps.setInt(4, lockInfo.getVersion() != null ? lockInfo.getVersion() : 0);
            ps.setInt(5, lockInfo.getReentrantCount() != null ? lockInfo.getReentrantCount() : 1);

            return ps.executeUpdate() > 0;

        } catch (SQLIntegrityConstraintViolationException e) {
            // 唯一索引冲突，锁已存在
            return false;
        } catch (SQLException e) {
            throw new LockException("Failed to insert lock: " + lockInfo.getLockName(), e);
        }
    }

    /**
     * 根据锁名称查询锁信息
     * 
     * @param lockName 锁名称
     * @return 锁信息
     */
    public Optional<LockInfo> findByLockName(String lockName) {
        String sql = "SELECT id, lock_name, lock_value, expire_time, version, reentrant_count, " +
                "create_time, update_time FROM distributed_lock WHERE lock_name = ?";

        try (Connection conn = dataSource.getConnection();
                PreparedStatement ps = conn.prepareStatement(sql)) {

            ps.setString(1, lockName);

            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(mapToLockInfo(rs));
                }
                return Optional.empty();
            }

        } catch (SQLException e) {
            throw new LockException("Failed to find lock: " + lockName, e);
        }
    }

    /**
     * 带悲观锁查询（FOR UPDATE）
     * 
     * @param lockName   锁名称
     * @param connection 连接（需要在事务中使用）
     * @return 锁信息
     */
    public Optional<LockInfo> findByLockNameForUpdate(String lockName, Connection connection) {
        String sql = "SELECT id, lock_name, lock_value, expire_time, version, reentrant_count, " +
                "create_time, update_time FROM distributed_lock WHERE lock_name = ? FOR UPDATE";

        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, lockName);

            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(mapToLockInfo(rs));
                }
                return Optional.empty();
            }

        } catch (SQLException e) {
            throw new LockException("Failed to find lock with FOR UPDATE: " + lockName, e);
        }
    }

    /**
     * 删除锁记录
     * 
     * @param lockName  锁名称
     * @param lockValue 锁值（确保只有持有者才能删除）
     * @return true-删除成功
     */
    public boolean deleteLock(String lockName, String lockValue) {
        String sql = "DELETE FROM distributed_lock WHERE lock_name = ? AND lock_value = ?";

        try (Connection conn = dataSource.getConnection();
                PreparedStatement ps = conn.prepareStatement(sql)) {

            ps.setString(1, lockName);
            ps.setString(2, lockValue);

            return ps.executeUpdate() > 0;

        } catch (SQLException e) {
            throw new LockException("Failed to delete lock: " + lockName, e);
        }
    }

    /**
     * 强制删除锁（用于清理过期锁）
     * 
     * @param lockName 锁名称
     * @return true-删除成功
     */
    public boolean forceDeleteLock(String lockName) {
        String sql = "DELETE FROM distributed_lock WHERE lock_name = ?";

        try (Connection conn = dataSource.getConnection();
                PreparedStatement ps = conn.prepareStatement(sql)) {

            ps.setString(1, lockName);
            return ps.executeUpdate() > 0;

        } catch (SQLException e) {
            throw new LockException("Failed to force delete lock: " + lockName, e);
        }
    }

    /**
     * 删除过期的锁
     * 
     * @param lockName 锁名称
     * @return true-删除成功（锁确实已过期）
     */
    public boolean deleteExpiredLock(String lockName) {
        String sql = "DELETE FROM distributed_lock WHERE lock_name = ? AND expire_time < ?";

        try (Connection conn = dataSource.getConnection();
                PreparedStatement ps = conn.prepareStatement(sql)) {

            ps.setString(1, lockName);
            ps.setTimestamp(2, Timestamp.valueOf(LocalDateTime.now()));

            return ps.executeUpdate() > 0;

        } catch (SQLException e) {
            throw new LockException("Failed to delete expired lock: " + lockName, e);
        }
    }

    /**
     * 更新锁的持有者（用于悲观锁抢占）
     * 
     * @param lockName     锁名称
     * @param newLockValue 新的锁值
     * @param expireTime   新的过期时间
     * @param connection   连接（需要在事务中使用）
     * @return true-更新成功
     */
    public boolean updateLockHolder(String lockName, String newLockValue,
            LocalDateTime expireTime, Connection connection) {
        String sql = "UPDATE distributed_lock SET lock_value = ?, expire_time = ?, " +
                "reentrant_count = 1, version = version + 1 WHERE lock_name = ?";

        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, newLockValue);
            ps.setTimestamp(2, Timestamp.valueOf(expireTime));
            ps.setString(3, lockName);

            return ps.executeUpdate() > 0;

        } catch (SQLException e) {
            throw new LockException("Failed to update lock holder: " + lockName, e);
        }
    }

    /**
     * 更新锁的过期时间（用于锁续期）
     * 
     * @param lockName      锁名称
     * @param lockValue     锁值（确保只有持有者才能续期）
     * @param newExpireTime 新的过期时间
     * @return true-续期成功
     */
    public boolean renewLock(String lockName, String lockValue, LocalDateTime newExpireTime) {
        String sql = "UPDATE distributed_lock SET expire_time = ?, version = version + 1 " +
                "WHERE lock_name = ? AND lock_value = ?";

        try (Connection conn = dataSource.getConnection();
                PreparedStatement ps = conn.prepareStatement(sql)) {

            ps.setTimestamp(1, Timestamp.valueOf(newExpireTime));
            ps.setString(2, lockName);
            ps.setString(3, lockValue);

            return ps.executeUpdate() > 0;

        } catch (SQLException e) {
            throw new LockException("Failed to renew lock: " + lockName, e);
        }
    }

    /**
     * 增加重入次数
     * 
     * @param lockName  锁名称
     * @param lockValue 锁值
     * @return true-成功
     */
    public boolean incrementReentrantCount(String lockName, String lockValue) {
        String sql = "UPDATE distributed_lock SET reentrant_count = reentrant_count + 1 " +
                "WHERE lock_name = ? AND lock_value = ?";

        try (Connection conn = dataSource.getConnection();
                PreparedStatement ps = conn.prepareStatement(sql)) {

            ps.setString(1, lockName);
            ps.setString(2, lockValue);

            return ps.executeUpdate() > 0;

        } catch (SQLException e) {
            throw new LockException("Failed to increment reentrant count: " + lockName, e);
        }
    }

    /**
     * 减少重入次数
     * 
     * @param lockName  锁名称
     * @param lockValue 锁值
     * @return 更新后的重入次数，-1表示更新失败
     */
    public int decrementReentrantCount(String lockName, String lockValue) {
        String sql = "UPDATE distributed_lock SET reentrant_count = reentrant_count - 1 " +
                "WHERE lock_name = ? AND lock_value = ? AND reentrant_count > 0";

        try (Connection conn = dataSource.getConnection();
                PreparedStatement ps = conn.prepareStatement(sql)) {

            ps.setString(1, lockName);
            ps.setString(2, lockValue);

            if (ps.executeUpdate() > 0) {
                // 查询更新后的重入次数
                Optional<LockInfo> lockInfo = findByLockName(lockName);
                return lockInfo.map(LockInfo::getReentrantCount).orElse(-1);
            }
            return -1;

        } catch (SQLException e) {
            throw new LockException("Failed to decrement reentrant count: " + lockName, e);
        }
    }

    /**
     * 乐观锁更新 - 使用版本号
     * 
     * @param lockName        锁名称
     * @param lockValue       锁值
     * @param expectedVersion 期望的版本号
     * @param newExpireTime   新的过期时间
     * @return true-更新成功（版本号匹配）
     */
    public boolean updateWithOptimisticLock(String lockName, String lockValue,
            int expectedVersion, LocalDateTime newExpireTime) {
        String sql = "UPDATE distributed_lock SET lock_value = ?, expire_time = ?, " +
                "version = version + 1 WHERE lock_name = ? AND version = ?";

        try (Connection conn = dataSource.getConnection();
                PreparedStatement ps = conn.prepareStatement(sql)) {

            ps.setString(1, lockValue);
            ps.setTimestamp(2, Timestamp.valueOf(newExpireTime));
            ps.setString(3, lockName);
            ps.setInt(4, expectedVersion);

            return ps.executeUpdate() > 0;

        } catch (SQLException e) {
            throw new LockException("Failed to update with optimistic lock: " + lockName, e);
        }
    }

    /**
     * 清理所有过期锁
     * 
     * @return 清理的锁数量
     */
    public int cleanExpiredLocks() {
        String sql = "DELETE FROM distributed_lock WHERE expire_time < ?";

        try (Connection conn = dataSource.getConnection();
                PreparedStatement ps = conn.prepareStatement(sql)) {

            ps.setTimestamp(1, Timestamp.valueOf(LocalDateTime.now()));
            return ps.executeUpdate();

        } catch (SQLException e) {
            throw new LockException("Failed to clean expired locks", e);
        }
    }

    /**
     * 获取数据库连接
     * 
     * @return 数据库连接
     */
    public Connection getConnection() throws SQLException {
        return dataSource.getConnection();
    }

    /**
     * 将ResultSet映射为LockInfo对象
     */
    private LockInfo mapToLockInfo(ResultSet rs) throws SQLException {
        return LockInfo.builder()
                .id(rs.getLong("id"))
                .lockName(rs.getString("lock_name"))
                .lockValue(rs.getString("lock_value"))
                .expireTime(rs.getTimestamp("expire_time").toLocalDateTime())
                .version(rs.getInt("version"))
                .reentrantCount(rs.getInt("reentrant_count"))
                .build();
    }
}
