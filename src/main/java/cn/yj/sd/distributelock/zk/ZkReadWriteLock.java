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
 * 基于 ZooKeeper 的分布式读写锁实现
 * 
 * 实现原理：
 * 1. 读锁：创建临时顺序节点 "read-XXXX"
 * - 获取锁：检查前面是否有写锁节点，如果没有则获取成功
 * - 如果有写锁节点，监听最近的写锁节点删除事件
 * 
 * 2. 写锁：创建临时顺序节点 "write-XXXX"
 * - 获取锁：检查是否为最小节点，如果是则获取成功
 * - 如果不是，监听前一个节点的删除事件
 * 
 * 特点：
 * - 读-读共享：多个读锁可以同时获取
 * - 读-写互斥：读锁和写锁互斥
 * - 写-写互斥：写锁之间互斥
 * - 公平性：按照请求顺序获取锁
 */
public class ZkReadWriteLock {

    /** 读锁节点前缀 */
    private static final String READ_LOCK_PREFIX = "read-";

    /** 写锁节点前缀 */
    private static final String WRITE_LOCK_PREFIX = "write-";

    /** ZooKeeper 客户端 */
    private final ZooKeeper zooKeeper;

    /** 锁的根路径 */
    private final String lockPath;

    /** 读锁 */
    private final ReadLock readLock;

    /** 写锁 */
    private final WriteLock writeLock;

    /**
     * 构造函数
     * 
     * @param zooKeeper ZooKeeper 客户端
     * @param lockPath  锁的根路径
     */
    public ZkReadWriteLock(ZooKeeper zooKeeper, String lockPath) {
        this.zooKeeper = zooKeeper;
        this.lockPath = lockPath;
        this.readLock = new ReadLock();
        this.writeLock = new WriteLock();
    }

    /**
     * 构造函数（使用 Helper）
     * 
     * @param helper   ZooKeeper 客户端辅助类
     * @param lockPath 锁的根路径
     */
    public ZkReadWriteLock(ZkClientHelper helper, String lockPath) {
        this(helper.getZooKeeper(), lockPath);
    }

    /**
     * 获取读锁
     */
    public ReadLock readLock() {
        return readLock;
    }

    /**
     * 获取写锁
     */
    public WriteLock writeLock() {
        return writeLock;
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
     * 读锁实现
     */
    public class ReadLock implements DistributedLock {

        /** 当前线程创建的锁节点路径 */
        private volatile String currentLockNode;

        /** 持有锁的线程 */
        private volatile Thread ownerThread;

        /** 重入计数 */
        private volatile int holdCount = 0;

        @Override
        public void lock() throws Exception {
            if (!tryLock(Long.MAX_VALUE, TimeUnit.MILLISECONDS)) {
                throw new IllegalStateException("Failed to acquire read lock");
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

            ensureLockPathExists();

            // 创建读锁节点
            currentLockNode = zooKeeper.create(
                    lockPath + "/" + READ_LOCK_PREFIX,
                    new byte[0],
                    ZooDefs.Ids.OPEN_ACL_UNSAFE,
                    CreateMode.EPHEMERAL_SEQUENTIAL);

            while (true) {
                // 检查超时
                if (timeout != Long.MAX_VALUE) {
                    long elapsed = System.currentTimeMillis() - startTime;
                    if (elapsed >= timeoutMillis) {
                        deleteCurrentNode();
                        return false;
                    }
                }

                // 获取所有子节点并排序
                List<String> children = zooKeeper.getChildren(lockPath, false);
                Collections.sort(children);

                String currentNodeName = currentLockNode.substring(lockPath.length() + 1);
                int currentIndex = children.indexOf(currentNodeName);

                // 查找前面是否有写锁节点
                String lastWriteNode = null;
                for (int i = 0; i < currentIndex; i++) {
                    String nodeName = children.get(i);
                    if (nodeName.startsWith(WRITE_LOCK_PREFIX)) {
                        lastWriteNode = nodeName;
                    }
                }

                if (lastWriteNode == null) {
                    // 前面没有写锁，获取成功
                    ownerThread = Thread.currentThread();
                    holdCount = 1;
                    return true;
                }

                if (timeout == 0) {
                    deleteCurrentNode();
                    return false;
                }

                // 监听最近的写锁节点
                String watchPath = lockPath + "/" + lastWriteNode;
                CountDownLatch latch = new CountDownLatch(1);
                Stat stat = zooKeeper.exists(watchPath, new Watcher() {
                    @Override
                    public void process(WatchedEvent event) {
                        if (event.getType() == Watcher.Event.EventType.NodeDeleted) {
                            latch.countDown();
                        }
                    }
                });

                if (stat != null) {
                    if (timeout == Long.MAX_VALUE) {
                        latch.await();
                    } else {
                        long remaining = timeoutMillis - (System.currentTimeMillis() - startTime);
                        if (remaining > 0) {
                            latch.await(remaining, TimeUnit.MILLISECONDS);
                        }
                    }
                }
            }
        }

        @Override
        public void unlock() throws Exception {
            if (!isHeldByCurrentThread()) {
                throw new IllegalStateException("Current thread does not hold this read lock");
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

        private void deleteCurrentNode() {
            if (currentLockNode != null) {
                try {
                    zooKeeper.delete(currentLockNode, -1);
                } catch (Exception e) {
                    // 忽略
                } finally {
                    currentLockNode = null;
                }
            }
        }
    }

    /**
     * 写锁实现
     */
    public class WriteLock implements DistributedLock {

        /** 当前线程创建的锁节点路径 */
        private volatile String currentLockNode;

        /** 持有锁的线程 */
        private volatile Thread ownerThread;

        /** 重入计数 */
        private volatile int holdCount = 0;

        @Override
        public void lock() throws Exception {
            if (!tryLock(Long.MAX_VALUE, TimeUnit.MILLISECONDS)) {
                throw new IllegalStateException("Failed to acquire write lock");
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

            ensureLockPathExists();

            // 创建写锁节点
            currentLockNode = zooKeeper.create(
                    lockPath + "/" + WRITE_LOCK_PREFIX,
                    new byte[0],
                    ZooDefs.Ids.OPEN_ACL_UNSAFE,
                    CreateMode.EPHEMERAL_SEQUENTIAL);

            while (true) {
                // 检查超时
                if (timeout != Long.MAX_VALUE) {
                    long elapsed = System.currentTimeMillis() - startTime;
                    if (elapsed >= timeoutMillis) {
                        deleteCurrentNode();
                        return false;
                    }
                }

                // 获取所有子节点并排序
                List<String> children = zooKeeper.getChildren(lockPath, false);
                Collections.sort(children);

                String currentNodeName = currentLockNode.substring(lockPath.length() + 1);
                int currentIndex = children.indexOf(currentNodeName);

                if (currentIndex == 0) {
                    // 当前节点是最小节点，获取成功
                    ownerThread = Thread.currentThread();
                    holdCount = 1;
                    return true;
                }

                if (timeout == 0) {
                    deleteCurrentNode();
                    return false;
                }

                // 监听前一个节点
                String prevNodeName = children.get(currentIndex - 1);
                String prevNodePath = lockPath + "/" + prevNodeName;

                CountDownLatch latch = new CountDownLatch(1);
                Stat stat = zooKeeper.exists(prevNodePath, new Watcher() {
                    @Override
                    public void process(WatchedEvent event) {
                        if (event.getType() == Watcher.Event.EventType.NodeDeleted) {
                            latch.countDown();
                        }
                    }
                });

                if (stat != null) {
                    if (timeout == Long.MAX_VALUE) {
                        latch.await();
                    } else {
                        long remaining = timeoutMillis - (System.currentTimeMillis() - startTime);
                        if (remaining > 0) {
                            latch.await(remaining, TimeUnit.MILLISECONDS);
                        }
                    }
                }
            }
        }

        @Override
        public void unlock() throws Exception {
            if (!isHeldByCurrentThread()) {
                throw new IllegalStateException("Current thread does not hold this write lock");
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

        private void deleteCurrentNode() {
            if (currentLockNode != null) {
                try {
                    zooKeeper.delete(currentLockNode, -1);
                } catch (Exception e) {
                    // 忽略
                } finally {
                    currentLockNode = null;
                }
            }
        }
    }
}
