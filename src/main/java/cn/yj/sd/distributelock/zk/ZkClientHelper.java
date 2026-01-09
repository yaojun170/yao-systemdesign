package cn.yj.sd.distributelock.zk;

import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.WatchedEvent;
import org.apache.zookeeper.Watcher;
import org.apache.zookeeper.ZooDefs;
import org.apache.zookeeper.ZooKeeper;

import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * ZooKeeper 客户端辅助类
 * 封装 ZooKeeper 连接管理和基础操作
 */
public class ZkClientHelper {

    /** 默认会话超时时间 */
    private static final int DEFAULT_SESSION_TIMEOUT = 30000;

    /** ZooKeeper 客户端实例 */
    private ZooKeeper zooKeeper;

    /** 连接地址 */
    private final String connectString;

    /** 会话超时时间 */
    private final int sessionTimeout;

    /** 连接状态监听器 */
    private final CountDownLatch connectedLatch = new CountDownLatch(1);

    /**
     * 构造函数
     * 
     * @param connectString ZooKeeper 连接地址，如 "localhost:2181"
     */
    public ZkClientHelper(String connectString) {
        this(connectString, DEFAULT_SESSION_TIMEOUT);
    }

    /**
     * 构造函数
     * 
     * @param connectString  ZooKeeper 连接地址
     * @param sessionTimeout 会话超时时间（毫秒）
     */
    public ZkClientHelper(String connectString, int sessionTimeout) {
        this.connectString = connectString;
        this.sessionTimeout = sessionTimeout;
    }

    /**
     * 连接到 ZooKeeper
     * 
     * @throws IOException          连接异常
     * @throws InterruptedException 等待连接时被中断
     */
    public void connect() throws IOException, InterruptedException {
        this.zooKeeper = new ZooKeeper(connectString, sessionTimeout, new Watcher() {
            @Override
            public void process(WatchedEvent event) {
                if (event.getState() == Event.KeeperState.SyncConnected) {
                    connectedLatch.countDown();
                }
            }
        });

        // 等待连接建立
        boolean connected = connectedLatch.await(sessionTimeout, TimeUnit.MILLISECONDS);
        if (!connected) {
            throw new IOException("Failed to connect to ZooKeeper within timeout: " + connectString);
        }
    }

    /**
     * 获取 ZooKeeper 客户端实例
     * 
     * @return ZooKeeper 实例
     */
    public ZooKeeper getZooKeeper() {
        return zooKeeper;
    }

    /**
     * 确保路径存在（创建持久节点）
     * 
     * @param path 路径
     * @throws KeeperException      ZooKeeper 异常
     * @throws InterruptedException 中断异常
     */
    public void ensurePath(String path) throws KeeperException, InterruptedException {
        if (path == null || path.isEmpty() || "/".equals(path)) {
            return;
        }

        if (zooKeeper.exists(path, false) != null) {
            return;
        }

        // 递归创建父路径
        int lastSlash = path.lastIndexOf('/');
        if (lastSlash > 0) {
            String parentPath = path.substring(0, lastSlash);
            ensurePath(parentPath);
        }

        // 创建当前节点
        try {
            zooKeeper.create(path, new byte[0], ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
        } catch (KeeperException.NodeExistsException e) {
            // 节点已存在，忽略
        }
    }

    /**
     * 关闭 ZooKeeper 连接
     */
    public void close() {
        if (zooKeeper != null) {
            try {
                zooKeeper.close();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /**
     * 检查连接是否有效
     * 
     * @return 是否连接
     */
    public boolean isConnected() {
        return zooKeeper != null && zooKeeper.getState().isConnected();
    }
}
