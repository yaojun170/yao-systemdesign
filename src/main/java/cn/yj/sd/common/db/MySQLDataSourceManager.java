package cn.yj.sd.common.db;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * MySQL数据源管理器
 * 负责管理HikariCP连接池的生命周期，支持多数据源
 *
 * @author yaojun
 */
public class MySQLDataSourceManager {

    // 默认数据源名称
    private static final String DEFAULT_DATASOURCE_NAME = "default";

    // 数据源缓存（支持多数据源）
    private static final ConcurrentMap<String, HikariDataSource> DATA_SOURCE_MAP = new ConcurrentHashMap<>();

    // 防止实例化
    private MySQLDataSourceManager() {
    }

    /**
     * 使用默认名称初始化数据源
     *
     * @param config MySQL配置
     * @return 初始化后的DataSource
     */
    public static DataSource initialize(MySQLConfig config) {
        return initialize(DEFAULT_DATASOURCE_NAME, config);
    }

    /**
     * 使用指定名称初始化数据源
     *
     * @param name   数据源名称
     * @param config MySQL配置
     * @return 初始化后的DataSource
     */
    public static synchronized DataSource initialize(String name, MySQLConfig config) {
        if (DATA_SOURCE_MAP.containsKey(name)) {
            throw new IllegalStateException("数据源已存在: " + name);
        }

        HikariConfig hikariConfig = new HikariConfig();
        hikariConfig.setJdbcUrl(config.getJdbcUrl());
        hikariConfig.setUsername(config.getUsername());
        hikariConfig.setPassword(config.getPassword());
        hikariConfig.setMaximumPoolSize(config.getMaxPoolSize());
        hikariConfig.setMinimumIdle(config.getMinIdle());
        hikariConfig.setConnectionTimeout(config.getConnectionTimeoutMs());
        hikariConfig.setIdleTimeout(config.getIdleTimeoutMs());
        hikariConfig.setMaxLifetime(config.getMaxLifetimeMs());
        hikariConfig.setPoolName(name + "-" + config.getPoolName());
        hikariConfig.setAutoCommit(config.isAutoCommit());

        if (config.getLeakDetectionThreshold() > 0) {
            hikariConfig.setLeakDetectionThreshold(config.getLeakDetectionThreshold());
        }

        // 添加一些性能优化参数
        hikariConfig.addDataSourceProperty("cachePrepStmts", "true");
        hikariConfig.addDataSourceProperty("prepStmtCacheSize", "250");
        hikariConfig.addDataSourceProperty("prepStmtCacheSqlLimit", "2048");
        hikariConfig.addDataSourceProperty("useServerPrepStmts", "true");

        HikariDataSource dataSource = new HikariDataSource(hikariConfig);
        DATA_SOURCE_MAP.put(name, dataSource);

        return dataSource;
    }

    /**
     * 获取默认数据源
     *
     * @return DataSource
     */
    public static DataSource getDataSource() {
        return getDataSource(DEFAULT_DATASOURCE_NAME);
    }

    /**
     * 根据名称获取数据源
     *
     * @param name 数据源名称
     * @return DataSource
     */
    public static DataSource getDataSource(String name) {
        DataSource dataSource = DATA_SOURCE_MAP.get(name);
        if (dataSource == null) {
            throw new IllegalStateException("数据源未初始化: " + name);
        }
        return dataSource;
    }

    /**
     * 从默认数据源获取连接
     *
     * @return Connection
     * @throws SQLException SQL异常
     */
    public static Connection getConnection() throws SQLException {
        return getConnection(DEFAULT_DATASOURCE_NAME);
    }

    /**
     * 从指定数据源获取连接
     *
     * @param name 数据源名称
     * @return Connection
     * @throws SQLException SQL异常
     */
    public static Connection getConnection(String name) throws SQLException {
        return getDataSource(name).getConnection();
    }

    /**
     * 关闭默认数据源
     */
    public static void shutdown() {
        shutdown(DEFAULT_DATASOURCE_NAME);
    }

    /**
     * 关闭指定数据源
     *
     * @param name 数据源名称
     */
    public static synchronized void shutdown(String name) {
        HikariDataSource dataSource = DATA_SOURCE_MAP.remove(name);
        if (dataSource != null && !dataSource.isClosed()) {
            dataSource.close();
        }
    }

    /**
     * 关闭所有数据源
     */
    public static synchronized void shutdownAll() {
        DATA_SOURCE_MAP.forEach((name, ds) -> {
            if (!ds.isClosed()) {
                ds.close();
            }
        });
        DATA_SOURCE_MAP.clear();
    }

    /**
     * 检查默认数据源是否可用
     *
     * @return 是否可用
     */
    public static boolean isAvailable() {
        return isAvailable(DEFAULT_DATASOURCE_NAME);
    }

    /**
     * 检查指定数据源是否可用
     *
     * @param name 数据源名称
     * @return 是否可用
     */
    public static boolean isAvailable(String name) {
        HikariDataSource dataSource = DATA_SOURCE_MAP.get(name);
        return dataSource != null && !dataSource.isClosed();
    }

    /**
     * 获取默认数据源的连接池统计信息
     *
     * @return 连接池统计信息
     */
    public static PoolStats getPoolStats() {
        return getPoolStats(DEFAULT_DATASOURCE_NAME);
    }

    /**
     * 获取指定数据源的连接池统计信息
     *
     * @param name 数据源名称
     * @return 连接池统计信息
     */
    public static PoolStats getPoolStats(String name) {
        HikariDataSource dataSource = DATA_SOURCE_MAP.get(name);
        if (dataSource == null) {
            return null;
        }

        return new PoolStats(
                dataSource.getHikariPoolMXBean().getTotalConnections(),
                dataSource.getHikariPoolMXBean().getActiveConnections(),
                dataSource.getHikariPoolMXBean().getIdleConnections(),
                dataSource.getHikariPoolMXBean().getThreadsAwaitingConnection());
    }

    /**
     * 连接池统计信息
     */
    public static class PoolStats {
        private final int totalConnections;
        private final int activeConnections;
        private final int idleConnections;
        private final int waitingThreads;

        public PoolStats(int totalConnections, int activeConnections,
                int idleConnections, int waitingThreads) {
            this.totalConnections = totalConnections;
            this.activeConnections = activeConnections;
            this.idleConnections = idleConnections;
            this.waitingThreads = waitingThreads;
        }

        public int getTotalConnections() {
            return totalConnections;
        }

        public int getActiveConnections() {
            return activeConnections;
        }

        public int getIdleConnections() {
            return idleConnections;
        }

        public int getWaitingThreads() {
            return waitingThreads;
        }

        @Override
        public String toString() {
            return "PoolStats{" +
                    "total=" + totalConnections +
                    ", active=" + activeConnections +
                    ", idle=" + idleConnections +
                    ", waiting=" + waitingThreads +
                    '}';
        }
    }
}
