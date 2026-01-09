package cn.yj.sd.distributelock.zk;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * ZooKeeper 分布式锁使用示例
 * 
 * 运行前请确保：
 * 1. ZooKeeper 服务已启动（默认端口 2181）
 * 2. 已添加 ZooKeeper 依赖
 */
public class ZkDistributedLockExample {

    /** ZooKeeper 连接地址 */
    private static final String ZK_ADDRESS = "localhost:2181";

    /** 锁路径 */
    private static final String LOCK_PATH = "/distributed-locks/example-lock";

    /** 读写锁路径 */
    private static final String RW_LOCK_PATH = "/distributed-locks/rw-lock";

    public static void main(String[] args) throws Exception {
        System.out.println("=== ZooKeeper 分布式锁示例 ===\n");

        // 示例1：基本的分布式锁
        System.out.println("【示例1】基本分布式锁");
        basicLockExample();

        Thread.sleep(1000);

        // 示例2：多线程竞争锁
        System.out.println("\n【示例2】多线程竞争锁");
        multiThreadLockExample();

        Thread.sleep(1000);

        // 示例3：可重入锁
        System.out.println("\n【示例3】可重入锁");
        reentrantLockExample();

        Thread.sleep(1000);

        // 示例4：超时获取锁
        System.out.println("\n【示例4】超时获取锁");
        timeoutLockExample();

        Thread.sleep(1000);

        // 示例5：读写锁
        System.out.println("\n【示例5】读写锁");
        readWriteLockExample();

        System.out.println("\n=== 所有示例执行完成 ===");
    }

    /**
     * 示例1：基本的分布式锁使用
     */
    private static void basicLockExample() throws Exception {
        ZkClientHelper helper = new ZkClientHelper(ZK_ADDRESS);
        helper.connect();

        ZkDistributedLock lock = new ZkDistributedLock(helper, LOCK_PATH);

        try {
            System.out.println("尝试获取锁...");
            lock.lock();
            System.out.println("成功获取锁，执行业务逻辑...");

            // 模拟业务操作
            Thread.sleep(1000);

            System.out.println("业务逻辑执行完成");
        } finally {
            lock.unlock();
            System.out.println("锁已释放");
            helper.close();
        }
    }

    /**
     * 示例2：多线程竞争锁
     */
    private static void multiThreadLockExample() throws Exception {
        int threadCount = 5;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch endLatch = new CountDownLatch(threadCount);
        AtomicInteger counter = new AtomicInteger(0);

        for (int i = 0; i < threadCount; i++) {
            final int threadId = i;
            executor.submit(() -> {
                ZkClientHelper helper = null;
                try {
                    helper = new ZkClientHelper(ZK_ADDRESS);
                    helper.connect();

                    ZkDistributedLock lock = new ZkDistributedLock(helper, LOCK_PATH + "-multi");

                    // 等待所有线程就绪
                    startLatch.await();

                    System.out.println("线程 " + threadId + " 尝试获取锁...");
                    lock.lock();

                    try {
                        int value = counter.incrementAndGet();
                        System.out.println("线程 " + threadId + " 获取锁成功，计数器: " + value);
                        Thread.sleep(200);
                    } finally {
                        lock.unlock();
                        System.out.println("线程 " + threadId + " 释放锁");
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                } finally {
                    if (helper != null) {
                        helper.close();
                    }
                    endLatch.countDown();
                }
            });
        }

        // 启动所有线程
        startLatch.countDown();

        // 等待所有线程完成
        endLatch.await();
        executor.shutdown();

        System.out.println("最终计数器值: " + counter.get());
    }

    /**
     * 示例3：可重入锁
     */
    private static void reentrantLockExample() throws Exception {
        ZkClientHelper helper = new ZkClientHelper(ZK_ADDRESS);
        helper.connect();

        ZkDistributedLock lock = new ZkDistributedLock(helper, LOCK_PATH + "-reentrant");

        try {
            lock.lock();
            System.out.println("第一次获取锁，holdCount: " + lock.getHoldCount());

            // 重入获取锁
            lock.lock();
            System.out.println("第二次获取锁（重入），holdCount: " + lock.getHoldCount());

            // 再次重入
            lock.lock();
            System.out.println("第三次获取锁（重入），holdCount: " + lock.getHoldCount());

            // 逐步释放
            lock.unlock();
            System.out.println("第一次释放，holdCount: " + lock.getHoldCount());

            lock.unlock();
            System.out.println("第二次释放，holdCount: " + lock.getHoldCount());

            lock.unlock();
            System.out.println("第三次释放，holdCount: " + lock.getHoldCount());
        } finally {
            helper.close();
        }
    }

    /**
     * 示例4：超时获取锁
     */
    private static void timeoutLockExample() throws Exception {
        ZkClientHelper helper1 = new ZkClientHelper(ZK_ADDRESS);
        ZkClientHelper helper2 = new ZkClientHelper(ZK_ADDRESS);
        helper1.connect();
        helper2.connect();

        String lockPath = LOCK_PATH + "-timeout";
        ZkDistributedLock lock1 = new ZkDistributedLock(helper1, lockPath);
        ZkDistributedLock lock2 = new ZkDistributedLock(helper2, lockPath);

        try {
            // 第一个客户端获取锁
            lock1.lock();
            System.out.println("客户端1 获取锁成功");

            // 第二个客户端尝试获取锁（超时）
            System.out.println("客户端2 尝试获取锁（超时2秒）...");
            long startTime = System.currentTimeMillis();
            boolean acquired = lock2.tryLock(2, TimeUnit.SECONDS);
            long elapsed = System.currentTimeMillis() - startTime;

            if (acquired) {
                System.out.println("客户端2 获取锁成功");
                lock2.unlock();
            } else {
                System.out.println("客户端2 获取锁超时，耗时: " + elapsed + "ms");
            }

            // 第一个客户端释放锁
            lock1.unlock();
            System.out.println("客户端1 释放锁");

            // 第二个客户端再次尝试
            System.out.println("客户端2 再次尝试获取锁...");
            if (lock2.tryLock(2, TimeUnit.SECONDS)) {
                System.out.println("客户端2 获取锁成功");
                lock2.unlock();
            }

        } finally {
            helper1.close();
            helper2.close();
        }
    }

    /**
     * 示例5：读写锁
     */
    private static void readWriteLockExample() throws Exception {
        int readerCount = 3;
        ExecutorService executor = Executors.newFixedThreadPool(readerCount + 1);
        CountDownLatch endLatch = new CountDownLatch(readerCount + 1);

        // 启动多个读线程
        for (int i = 0; i < readerCount; i++) {
            final int readerId = i;
            executor.submit(() -> {
                ZkClientHelper helper = null;
                try {
                    helper = new ZkClientHelper(ZK_ADDRESS);
                    helper.connect();

                    ZkReadWriteLock rwLock = new ZkReadWriteLock(helper, RW_LOCK_PATH);
                    DistributedLock readLock = rwLock.readLock();

                    readLock.lock();
                    try {
                        System.out.println("读者 " + readerId + " 获取读锁成功");
                        Thread.sleep(1000);
                        System.out.println("读者 " + readerId + " 读取完成");
                    } finally {
                        readLock.unlock();
                        System.out.println("读者 " + readerId + " 释放读锁");
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                } finally {
                    if (helper != null) {
                        helper.close();
                    }
                    endLatch.countDown();
                }
            });
        }

        // 启动写线程
        executor.submit(() -> {
            ZkClientHelper helper = null;
            try {
                Thread.sleep(200); // 稍微延迟，让读者先开始

                helper = new ZkClientHelper(ZK_ADDRESS);
                helper.connect();

                ZkReadWriteLock rwLock = new ZkReadWriteLock(helper, RW_LOCK_PATH);
                DistributedLock writeLock = rwLock.writeLock();

                System.out.println("写者尝试获取写锁...");
                writeLock.lock();
                try {
                    System.out.println("写者获取写锁成功");
                    Thread.sleep(500);
                    System.out.println("写者写入完成");
                } finally {
                    writeLock.unlock();
                    System.out.println("写者释放写锁");
                }
            } catch (Exception e) {
                e.printStackTrace();
            } finally {
                if (helper != null) {
                    helper.close();
                }
                endLatch.countDown();
            }
        });

        endLatch.await();
        executor.shutdown();
    }
}
