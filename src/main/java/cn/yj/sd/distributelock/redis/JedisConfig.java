package cn.yj.sd.distributelock.redis;

import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;

/**
 * Jedis连接池配置和管理
 * 提供全局的Jedis连接池实例
 * 
 * @author yaojun
 */
public class JedisConfig {

    private static volatile JedisPool jedisPool;

    /** 默认Redis主机 */
    private static final String DEFAULT_HOST = "localhost";
    /** 默认Redis端口 */
    private static final int DEFAULT_PORT = 6379;
    /** 默认连接超时时间（毫秒） */
    private static final int DEFAULT_TIMEOUT = 2000;
    /** 默认最大连接数 */
    private static final int DEFAULT_MAX_TOTAL = 50;
    /** 默认最大空闲连接数 */
    private static final int DEFAULT_MAX_IDLE = 10;
    /** 默认最小空闲连接数 */
    private static final int DEFAULT_MIN_IDLE = 5;

    private JedisConfig() {
    }

    /**
     * 获取Jedis连接池（使用默认配置）
     */
    public static JedisPool getJedisPool() {
        if (jedisPool == null) {
            synchronized (JedisConfig.class) {
                if (jedisPool == null) {
                    jedisPool = createJedisPool(DEFAULT_HOST, DEFAULT_PORT, null, DEFAULT_TIMEOUT);
                }
            }
        }
        return jedisPool;
    }

    /**
     * 初始化Jedis连接池
     * 
     * @param host     Redis主机
     * @param port     Redis端口
     * @param password 密码（可为null）
     */
    public static void init(String host, int port, String password) {
        init(host, port, password, DEFAULT_TIMEOUT);
    }

    /**
     * 初始化Jedis连接池
     * 
     * @param host     Redis主机
     * @param port     Redis端口
     * @param password 密码（可为null）
     * @param timeout  连接超时（毫秒）
     */
    public static synchronized void init(String host, int port, String password, int timeout) {
        if (jedisPool != null) {
            jedisPool.close();
        }
        jedisPool = createJedisPool(host, port, password, timeout);
    }

    /**
     * 创建Jedis连接池
     */
    private static JedisPool createJedisPool(String host, int port, String password, int timeout) {
        JedisPoolConfig poolConfig = new JedisPoolConfig();
        poolConfig.setMaxTotal(DEFAULT_MAX_TOTAL);
        poolConfig.setMaxIdle(DEFAULT_MAX_IDLE);
        poolConfig.setMinIdle(DEFAULT_MIN_IDLE);
        poolConfig.setTestOnBorrow(true);
        poolConfig.setTestOnReturn(true);
        poolConfig.setTestWhileIdle(true);
        poolConfig.setBlockWhenExhausted(true);

        if (password != null && !password.isEmpty()) {
            return new JedisPool(poolConfig, host, port, timeout, password);
        } else {
            return new JedisPool(poolConfig, host, port, timeout);
        }
    }

    /**
     * 关闭连接池
     */
    public static synchronized void close() {
        if (jedisPool != null) {
            jedisPool.close();
            jedisPool = null;
        }
    }
}
