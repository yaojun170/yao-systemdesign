package cn.yj.sd.distributelock.redis;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Redis分布式锁使用示例
 * 
 * @author yaojun
 */
public class RedisLockExample {

    public static void main(String[] args) throws Exception {
        // 初始化Redis连接（根据实际环境修改）
        JedisConfig.init("localhost", 6379, null);

        try {
            System.out.println("=== Redis分布式锁示例 ===\n");

            // 1. 基本使用示例
            basicUsageExample();

            // 2. 并发竞争示例
            concurrentExample();

            // 3. 可重入锁示例
            reentrantExample();

            // 4. 超时等待示例
            timeoutExample();

            // 5. 看门狗自动续期示例
            watchdogExample();

        } finally {
            // 关闭连接池
            JedisConfig.close();
        }
    }

    /**
     * 基本使用示例
     */
    private static void basicUsageExample() {
        System.out.println("【1. 基本使用示例】");

        RedisDistributedLock lock = new RedisDistributedLock("basic-lock");

        if (lock.tryLock()) {
            try {
                System.out.println("获取锁成功，执行业务逻辑...");
                Thread.sleep(100);
                System.out.println("业务逻辑执行完成");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                lock.unlock();
                System.out.println("锁已释放");
            }
        } else {
            System.out.println("获取锁失败");
        }

        System.out.println();
    }

    /**
     * 并发竞争示例
     */
    private static void concurrentExample() throws InterruptedException {
        System.out.println("【2. 并发竞争示例】");

        final int THREAD_COUNT = 10;
        final AtomicInteger counter = new AtomicInteger(0);
        final AtomicInteger successCount = new AtomicInteger(0);
        final CountDownLatch latch = new CountDownLatch(THREAD_COUNT);
        final ExecutorService executor = Executors.newFixedThreadPool(THREAD_COUNT);

        for (int i = 0; i < THREAD_COUNT; i++) {
            final int threadId = i;
            executor.submit(() -> {
                RedisDistributedLock lock = new RedisDistributedLock("concurrent-lock", 5000, false);
                try {
                    // 尝试在1秒内获取锁
                    if (lock.tryLock(1, TimeUnit.SECONDS)) {
                        try {
                            successCount.incrementAndGet();
                            // 模拟业务操作
                            int value = counter.get();
                            Thread.sleep(10);
                            counter.set(value + 1);
                            System.out.println("线程 " + threadId + " 获取锁并执行操作，counter=" + counter.get());
                        } finally {
                            lock.unlock();
                        }
                    } else {
                        System.out.println("线程 " + threadId + " 获取锁超时");
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await();
        executor.shutdown();

        System.out.println("最终counter值: " + counter.get() + ", 获取锁成功次数: " + successCount.get());
        System.out.println();
    }

    /**
     * 可重入锁示例
     */
    private static void reentrantExample() {
        System.out.println("【3. 可重入锁示例】");

        RedisDistributedLock lock = new RedisDistributedLock("reentrant-lock");

        // 第一次获取锁
        if (lock.tryLock()) {
            System.out.println("第一次获取锁成功");

            // 第二次获取同一把锁（可重入）
            if (lock.tryLock()) {
                System.out.println("第二次获取锁成功（可重入）");

                // 第三次获取同一把锁
                if (lock.tryLock()) {
                    System.out.println("第三次获取锁成功（可重入）");

                    // 依次释放
                    lock.unlock();
                    System.out.println("释放一次锁，isHeld=" + lock.isHeldByCurrentThread());
                }

                lock.unlock();
                System.out.println("释放二次锁，isHeld=" + lock.isHeldByCurrentThread());
            }

            lock.unlock();
            System.out.println("释放三次锁，isHeld=" + lock.isHeldByCurrentThread());
        }

        System.out.println();
    }

    /**
     * 超时等待示例
     */
    private static void timeoutExample() throws InterruptedException {
        System.out.println("【4. 超时等待示例】");

        // 线程1持有锁
        Thread holder = new Thread(() -> {
            RedisDistributedLock lock = new RedisDistributedLock("timeout-lock", 5000, false);
            if (lock.tryLock()) {
                try {
                    System.out.println("持有者线程获取锁，将持有2秒...");
                    Thread.sleep(2000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    lock.unlock();
                    System.out.println("持有者线程释放锁");
                }
            }
        });

        // 线程2等待锁
        Thread waiter = new Thread(() -> {
            try {
                Thread.sleep(100); // 确保holder先执行
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }

            RedisDistributedLock lock = new RedisDistributedLock("timeout-lock", 5000, false);
            long start = System.currentTimeMillis();

            if (lock.tryLock(3, TimeUnit.SECONDS)) {
                try {
                    long waited = System.currentTimeMillis() - start;
                    System.out.println("等待者线程获取锁成功，等待了 " + waited + " ms");
                } finally {
                    lock.unlock();
                }
            } else {
                System.out.println("等待者线程获取锁超时");
            }
        });

        holder.start();
        waiter.start();
        holder.join();
        waiter.join();

        System.out.println();
    }

    /**
     * 看门狗自动续期示例
     */
    private static void watchdogExample() throws InterruptedException {
        System.out.println("【5. 看门狗自动续期示例】");
        System.out.println("锁过期时间设置为3秒，业务执行5秒，看门狗会自动续期...");

        RedisDistributedLock lock = new RedisDistributedLock("watchdog-lock", 3000, true);

        if (lock.tryLock()) {
            try {
                System.out.println("获取锁成功，开始执行长时间任务...");

                for (int i = 1; i <= 5; i++) {
                    Thread.sleep(1000);
                    System.out.println("任务执行中... " + i + "秒");
                }

                System.out.println("长时间任务执行完成");
            } finally {
                lock.unlock();
                System.out.println("锁已释放");
            }
        }

        System.out.println("\n=== 所有示例执行完成 ===");
    }
}
