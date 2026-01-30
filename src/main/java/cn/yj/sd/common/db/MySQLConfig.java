package cn.yj.sd.common.db;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/**
 * MySQL数据库配置类
 * 支持从properties文件读取配置或使用Builder模式构建
 *
 * @author yaojun
 */
public class MySQLConfig {

    private final String jdbcUrl;
    private final String username;
    private final String password;
    private final int maxPoolSize;
    private final int minIdle;
    private final long connectionTimeoutMs;
    private final long idleTimeoutMs;
    private final long maxLifetimeMs;
    private final String poolName;
    private final boolean autoCommit;
    private final int leakDetectionThreshold;

    private MySQLConfig(Builder builder) {
        this.jdbcUrl = builder.jdbcUrl;
        this.username = builder.username;
        this.password = builder.password;
        this.maxPoolSize = builder.maxPoolSize;
        this.minIdle = builder.minIdle;
        this.connectionTimeoutMs = builder.connectionTimeoutMs;
        this.idleTimeoutMs = builder.idleTimeoutMs;
        this.maxLifetimeMs = builder.maxLifetimeMs;
        this.poolName = builder.poolName;
        this.autoCommit = builder.autoCommit;
        this.leakDetectionThreshold = builder.leakDetectionThreshold;
    }

    /**
     * 从classpath下的properties文件加载配置
     *
     * @param propertiesFile properties文件名（相对于classpath）
     * @return MySQLConfig实例
     */
    public static MySQLConfig fromProperties(String propertiesFile) {
        Properties props = new Properties();
        try (InputStream is = MySQLConfig.class.getClassLoader().getResourceAsStream(propertiesFile)) {
            if (is == null) {
                throw new IllegalArgumentException("配置文件不存在: " + propertiesFile);
            }
            props.load(is);
        } catch (IOException e) {
            throw new RuntimeException("加载配置文件失败: " + propertiesFile, e);
        }
        return fromProperties(props);
    }

    /**
     * 从Properties对象加载配置
     *
     * @param props Properties对象
     * @return MySQLConfig实例
     */
    public static MySQLConfig fromProperties(Properties props) {
        return new Builder()
                .jdbcUrl(props.getProperty("mysql.jdbc.url"))
                .username(props.getProperty("mysql.username"))
                .password(props.getProperty("mysql.password"))
                .maxPoolSize(getIntProperty(props, "mysql.pool.maxSize", 10))
                .minIdle(getIntProperty(props, "mysql.pool.minIdle", 2))
                .connectionTimeoutMs(getLongProperty(props, "mysql.pool.connectionTimeoutMs", 30000))
                .idleTimeoutMs(getLongProperty(props, "mysql.pool.idleTimeoutMs", 600000))
                .maxLifetimeMs(getLongProperty(props, "mysql.pool.maxLifetimeMs", 1800000))
                .poolName(props.getProperty("mysql.pool.name", "MySQLPool"))
                .autoCommit(getBooleanProperty(props, "mysql.autoCommit", true))
                .leakDetectionThreshold(getIntProperty(props, "mysql.pool.leakDetectionThreshold", 0))
                .build();
    }

    private static int getIntProperty(Properties props, String key, int defaultValue) {
        String value = props.getProperty(key);
        return value != null ? Integer.parseInt(value) : defaultValue;
    }

    private static long getLongProperty(Properties props, String key, long defaultValue) {
        String value = props.getProperty(key);
        return value != null ? Long.parseLong(value) : defaultValue;
    }

    private static boolean getBooleanProperty(Properties props, String key, boolean defaultValue) {
        String value = props.getProperty(key);
        return value != null ? Boolean.parseBoolean(value) : defaultValue;
    }

    // Getters
    public String getJdbcUrl() {
        return jdbcUrl;
    }

    public String getUsername() {
        return username;
    }

    public String getPassword() {
        return password;
    }

    public int getMaxPoolSize() {
        return maxPoolSize;
    }

    public int getMinIdle() {
        return minIdle;
    }

    public long getConnectionTimeoutMs() {
        return connectionTimeoutMs;
    }

    public long getIdleTimeoutMs() {
        return idleTimeoutMs;
    }

    public long getMaxLifetimeMs() {
        return maxLifetimeMs;
    }

    public String getPoolName() {
        return poolName;
    }

    public boolean isAutoCommit() {
        return autoCommit;
    }

    public int getLeakDetectionThreshold() {
        return leakDetectionThreshold;
    }

    @Override
    public String toString() {
        return "MySQLConfig{" +
                "jdbcUrl='" + jdbcUrl + '\'' +
                ", username='" + username + '\'' +
                ", maxPoolSize=" + maxPoolSize +
                ", minIdle=" + minIdle +
                ", poolName='" + poolName + '\'' +
                '}';
    }

    /**
     * Builder模式构建MySQLConfig
     */
    public static class Builder {
        private String jdbcUrl;
        private String username;
        private String password;
        private int maxPoolSize = 10;
        private int minIdle = 2;
        private long connectionTimeoutMs = 30000;
        private long idleTimeoutMs = 600000;
        private long maxLifetimeMs = 1800000;
        private String poolName = "MySQLPool";
        private boolean autoCommit = true;
        private int leakDetectionThreshold = 0;

        public Builder jdbcUrl(String jdbcUrl) {
            this.jdbcUrl = jdbcUrl;
            return this;
        }

        public Builder username(String username) {
            this.username = username;
            return this;
        }

        public Builder password(String password) {
            this.password = password;
            return this;
        }

        public Builder maxPoolSize(int maxPoolSize) {
            this.maxPoolSize = maxPoolSize;
            return this;
        }

        public Builder minIdle(int minIdle) {
            this.minIdle = minIdle;
            return this;
        }

        public Builder connectionTimeoutMs(long connectionTimeoutMs) {
            this.connectionTimeoutMs = connectionTimeoutMs;
            return this;
        }

        public Builder idleTimeoutMs(long idleTimeoutMs) {
            this.idleTimeoutMs = idleTimeoutMs;
            return this;
        }

        public Builder maxLifetimeMs(long maxLifetimeMs) {
            this.maxLifetimeMs = maxLifetimeMs;
            return this;
        }

        public Builder poolName(String poolName) {
            this.poolName = poolName;
            return this;
        }

        public Builder autoCommit(boolean autoCommit) {
            this.autoCommit = autoCommit;
            return this;
        }

        public Builder leakDetectionThreshold(int leakDetectionThreshold) {
            this.leakDetectionThreshold = leakDetectionThreshold;
            return this;
        }

        public MySQLConfig build() {
            validate();
            return new MySQLConfig(this);
        }

        private void validate() {
            if (jdbcUrl == null || jdbcUrl.isEmpty()) {
                throw new IllegalArgumentException("jdbcUrl不能为空");
            }
            if (username == null || username.isEmpty()) {
                throw new IllegalArgumentException("username不能为空");
            }
            if (password == null) {
                throw new IllegalArgumentException("password不能为空");
            }
        }
    }
}
