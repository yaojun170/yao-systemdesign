package cn.yj.sd.distributelock.zk;

import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.WatchedEvent;
import org.apache.zookeeper.Watcher;
import org.apache.zookeeper.ZooDefs;
import org.apache.zookeeper.ZooKeeper;
import org.apache.zookeeper.data.Stat;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * 基于 ZooKeeper 临时顺序节点的分布式锁实现
 * 
 * 实现原理：
 * 1. 在指定锁路径下创建临时顺序节点 (EPHEMERAL_SEQUENTIAL)
 * 2. 获取锁路径下所有子节点并排序
 * 3. 判断当前节点是否为最小节点：
 * - 是：成功获取锁
 * - 否：监听前一个节点的删除事件，等待通知后重新判断
 * 4. 释放锁时删除当前节点
 * 
 * 优点：
 * - 公平锁：按照请求顺序获取锁
 * - 避免惊群效应：只监听前一个节点，而不是所有客户端都监听同一个节点
 * - 自动释放：临时节点在客户端断开连接时自动删除
 */
public class ZkDistributedLock implements DistributedLock {

    /** 锁节点前缀 */
    private static final String LOCK_NODE_PREFIX = "lock-";

    /** ZooKeeper 客户端 */
    private final ZooKeeper zooKeeper;

    /** 锁的根路径 */
    private final String lockPath;

    /** 当前线程创建的锁节点路径 */
    private volatile String currentLockNode;

    /** 持有锁的线程 */
    private volatile Thread ownerThread;

    /** 重入计数 */
    private volatile int holdCount = 0;

    /**
     * 构造函数
     * 
     * @param zooKeeper ZooKeeper 客户端
     * @param lockPath  锁的根路径，如 "/locks/mylock"
     */
    public ZkDistributedLock(ZooKeeper zooKeeper, String lockPath) {
        this.zooKeeper = zooKeeper;
        this.lockPath = lockPath;
    }

    /**
     * 构造函数（使用 Helper）
     * 
     * @param helper   ZooKeeper 客户端辅助类
     * @param lockPath 锁的根路径
     */
    public ZkDistributedLock(ZkClientHelper helper, String lockPath) {
        this(helper.getZooKeeper(), lockPath);
    }

    @Override
    public void lock() throws Exception {
        if (!tryLock(Long.MAX_VALUE, TimeUnit.MILLISECONDS)) {
            throw new IllegalStateException("Failed to acquire lock");
        }
    }

    @Override
    public boolean tryLock() throws Exception {
        return tryLock(0, TimeUnit.MILLISECONDS);
    }

    @Override
    public boolean tryLock(long timeout, TimeUnit unit) throws Exception {
        long startTime = System.currentTimeMillis();
        long timeoutMillis = unit.toMillis(timeout);

        // 检查重入
        if (isHeldByCurrentThread()) {
            holdCount++;
            return true;
        }

        // 确保锁路径存在
        ensureLockPathExists();

        // 创建临时顺序节点
        currentLockNode = zooKeeper.create(
                lockPath + "/" + LOCK_NODE_PREFIX,
                new byte[0],
                ZooDefs.Ids.OPEN_ACL_UNSAFE,
                CreateMode.EPHEMERAL_SEQUENTIAL);

        // 尝试获取锁
        while (true) {
            // 检查超时
            if (timeout != Long.MAX_VALUE) {
                long elapsed = System.currentTimeMillis() - startTime;
                if (elapsed >= timeoutMillis) {
                    // 超时，删除创建的节点
                    deleteCurrentNode();
                    return false;
                }
            }

            // 获取所有子节点并排序
            List<String> children = zooKeeper.getChildren(lockPath, false);
            Collections.sort(children);

            // 获取当前节点名称
            String currentNodeName = currentLockNode.substring(lockPath.length() + 1);
            int currentIndex = children.indexOf(currentNodeName);

            if (currentIndex < 0) {
                // 节点不存在，可能已被删除
                throw new IllegalStateException("Current lock node not found: " + currentNodeName);
            }

            if (currentIndex == 0) {
                // 当前节点是最小节点，成功获取锁
                ownerThread = Thread.currentThread();
                holdCount = 1;
                return true;
            }

            // 非立即返回模式，监听前一个节点
            if (timeout == 0) {
                deleteCurrentNode();
                return false;
            }

            // 获取前一个节点
            String prevNodeName = children.get(currentIndex - 1);
            String prevNodePath = lockPath + "/" + prevNodeName;

            // 等待前一个节点被删除
            CountDownLatch latch = new CountDownLatch(1);
            Stat stat = zooKeeper.exists(prevNodePath, new Watcher() {
                @Override
                public void process(WatchedEvent event) {
                    if (event.getType() == Event.EventType.NodeDeleted ||
                            event.getType() == Event.EventType.None) {
                        latch.countDown();
                    }
                }
            });

            if (stat != null) {
                // 前一个节点存在，等待
                if (timeout == Long.MAX_VALUE) {
                    latch.await();
                } else {
                    long remaining = timeoutMillis - (System.currentTimeMillis() - startTime);
                    if (remaining > 0) {
                        latch.await(remaining, TimeUnit.MILLISECONDS);
                    }
                }
            }
            // 如果 stat 为 null，说明前一个节点已经被删除，继续循环检查
        }
    }

    @Override
    public void unlock() throws Exception {
        if (!isHeldByCurrentThread()) {
            throw new IllegalStateException("Current thread does not hold this lock");
        }

        holdCount--;

        if (holdCount == 0) {
            ownerThread = null;
            deleteCurrentNode();
        }
    }

    @Override
    public boolean isHeldByCurrentThread() {
        return Thread.currentThread() == ownerThread;
    }

    /**
     * 获取当前锁节点路径
     * 
     * @return 锁节点路径
     */
    public String getCurrentLockNode() {
        return currentLockNode;
    }

    /**
     * 获取重入次数
     * 
     * @return 重入次数
     */
    public int getHoldCount() {
        return holdCount;
    }

    /**
     * 确保锁路径存在
     */
    private void ensureLockPathExists() throws KeeperException, InterruptedException {
        try {
            if (zooKeeper.exists(lockPath, false) == null) {
                createPathRecursively(lockPath);
            }
        } catch (KeeperException.NodeExistsException e) {
            // 忽略
        }
    }

    /**
     * 递归创建路径
     */
    private void createPathRecursively(String path) throws KeeperException, InterruptedException {
        if (path == null || path.isEmpty() || "/".equals(path)) {
            return;
        }

        if (zooKeeper.exists(path, false) != null) {
            return;
        }

        int lastSlash = path.lastIndexOf('/');
        if (lastSlash > 0) {
            createPathRecursively(path.substring(0, lastSlash));
        }

        try {
            zooKeeper.create(path, new byte[0], ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
        } catch (KeeperException.NodeExistsException e) {
            // 忽略
        }
    }

    /**
     * 删除当前锁节点
     */
    private void deleteCurrentNode() {
        if (currentLockNode != null) {
            try {
                zooKeeper.delete(currentLockNode, -1);
            } catch (KeeperException.NoNodeException e) {
                // 节点已被删除，忽略
            } catch (Exception e) {
                // 忽略其他异常
            } finally {
                currentLockNode = null;
            }
        }
    }
}
