package cn.yj.sd.common.db;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * 事务管理模板类
 * 提供编程式事务管理，支持事务传播、隔离级别、保存点等
 *
 * @author yaojun
 */
public class TransactionTemplate {

    private final DataSource dataSource;
    private final JdbcTemplate jdbcTemplate;

    // ThreadLocal保存当前事务连接
    private static final ThreadLocal<Connection> CURRENT_CONNECTION = new ThreadLocal<>();
    private static final ThreadLocal<Integer> TRANSACTION_DEPTH = ThreadLocal.withInitial(() -> 0);

    /**
     * 事务隔离级别
     */
    public enum IsolationLevel {
        DEFAULT(-1),
        READ_UNCOMMITTED(Connection.TRANSACTION_READ_UNCOMMITTED),
        READ_COMMITTED(Connection.TRANSACTION_READ_COMMITTED),
        REPEATABLE_READ(Connection.TRANSACTION_REPEATABLE_READ),
        SERIALIZABLE(Connection.TRANSACTION_SERIALIZABLE);

        private final int level;

        IsolationLevel(int level) {
            this.level = level;
        }

        public int getLevel() {
            return level;
        }
    }

    /**
     * 使用指定数据源创建TransactionTemplate
     *
     * @param dataSource 数据源
     */
    public TransactionTemplate(DataSource dataSource) {
        if (dataSource == null) {
            throw new IllegalArgumentException("DataSource不能为空");
        }
        this.dataSource = dataSource;
        this.jdbcTemplate = new JdbcTemplate(dataSource);
    }

    /**
     * 使用默认数据源创建TransactionTemplate
     */
    public TransactionTemplate() {
        this(MySQLDataSourceManager.getDataSource());
    }

    /**
     * 获取JdbcTemplate
     *
     * @return JdbcTemplate实例
     */
    public JdbcTemplate getJdbcTemplate() {
        return jdbcTemplate;
    }

    /**
     * 在事务中执行操作（有返回值）
     *
     * @param action 事务操作
     * @param <T>    返回类型
     * @return 操作结果
     */
    public <T> T execute(Function<Connection, T> action) {
        return execute(action, IsolationLevel.DEFAULT);
    }

    /**
     * 在事务中执行操作（有返回值），指定隔离级别
     *
     * @param action         事务操作
     * @param isolationLevel 隔离级别
     * @param <T>            返回类型
     * @return 操作结果
     */
    public <T> T execute(Function<Connection, T> action, IsolationLevel isolationLevel) {
        Connection existingConn = CURRENT_CONNECTION.get();

        // 如果已存在事务，使用嵌套事务（Savepoint）
        if (existingConn != null) {
            return executeNested(existingConn, action);
        }

        // 开始新事务
        Connection conn = null;
        Boolean originalAutoCommit = null;
        Integer originalIsolation = null;

        try {
            conn = dataSource.getConnection();
            originalAutoCommit = conn.getAutoCommit();

            // 设置隔离级别
            if (isolationLevel != IsolationLevel.DEFAULT) {
                originalIsolation = conn.getTransactionIsolation();
                conn.setTransactionIsolation(isolationLevel.getLevel());
            }

            conn.setAutoCommit(false);
            CURRENT_CONNECTION.set(conn);
            TRANSACTION_DEPTH.set(1);

            T result = action.apply(conn);

            conn.commit();
            return result;

        } catch (Exception e) {
            rollbackQuietly(conn);
            throw new TransactionException("事务执行失败", e);
        } finally {
            CURRENT_CONNECTION.remove();
            TRANSACTION_DEPTH.remove();
            restoreConnection(conn, originalAutoCommit, originalIsolation);
        }
    }

    /**
     * 在事务中执行操作（无返回值）
     *
     * @param action 事务操作
     */
    public void executeWithoutResult(Consumer<Connection> action) {
        executeWithoutResult(action, IsolationLevel.DEFAULT);
    }

    /**
     * 在事务中执行操作（无返回值），指定隔离级别
     *
     * @param action         事务操作
     * @param isolationLevel 隔离级别
     */
    public void executeWithoutResult(Consumer<Connection> action, IsolationLevel isolationLevel) {
        execute(conn -> {
            action.accept(conn);
            return null;
        }, isolationLevel);
    }

    /**
     * 执行嵌套事务（使用Savepoint）
     */
    private <T> T executeNested(Connection conn, Function<Connection, T> action) {
        Savepoint savepoint = null;
        int depth = TRANSACTION_DEPTH.get();
        TRANSACTION_DEPTH.set(depth + 1);

        try {
            savepoint = conn.setSavepoint("SAVEPOINT_" + System.nanoTime());
            return action.apply(conn);
        } catch (Exception e) {
            if (savepoint != null) {
                rollbackToSavepoint(conn, savepoint);
            }
            throw new TransactionException("嵌套事务执行失败", e);
        } finally {
            TRANSACTION_DEPTH.set(depth);
            releaseSavepoint(conn, savepoint);
        }
    }

    /**
     * 获取当前事务连接（如果存在）
     *
     * @return 当前事务连接，不存在返回null
     */
    public static Connection getCurrentConnection() {
        return CURRENT_CONNECTION.get();
    }

    /**
     * 检查当前是否在事务中
     *
     * @return 是否在事务中
     */
    public static boolean isInTransaction() {
        return CURRENT_CONNECTION.get() != null;
    }

    /**
     * 获取当前事务深度
     *
     * @return 事务深度（0表示不在事务中）
     */
    public static int getTransactionDepth() {
        return TRANSACTION_DEPTH.get();
    }

    // ==================== 工具方法 ====================

    private void rollbackQuietly(Connection conn) {
        if (conn != null) {
            try {
                conn.rollback();
            } catch (SQLException e) {
                // 忽略回滚异常
            }
        }
    }

    private void rollbackToSavepoint(Connection conn, Savepoint savepoint) {
        try {
            conn.rollback(savepoint);
        } catch (SQLException e) {
            // 忽略回滚异常
        }
    }

    private void releaseSavepoint(Connection conn, Savepoint savepoint) {
        if (savepoint != null) {
            try {
                conn.releaseSavepoint(savepoint);
            } catch (SQLException e) {
                // 某些数据库不支持释放保存点，忽略
            }
        }
    }

    private void restoreConnection(Connection conn, Boolean originalAutoCommit, Integer originalIsolation) {
        if (conn != null) {
            try {
                if (originalAutoCommit != null) {
                    conn.setAutoCommit(originalAutoCommit);
                }
                if (originalIsolation != null) {
                    conn.setTransactionIsolation(originalIsolation);
                }
            } catch (SQLException e) {
                // 忽略
            } finally {
                closeQuietly(conn);
            }
        }
    }

    private void closeQuietly(Connection conn) {
        try {
            if (conn != null && !conn.isClosed()) {
                conn.close();
            }
        } catch (SQLException e) {
            // 忽略
        }
    }

    /**
     * 事务异常
     */
    public static class TransactionException extends RuntimeException {
        public TransactionException(String message) {
            super(message);
        }

        public TransactionException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
