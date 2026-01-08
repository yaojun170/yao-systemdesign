package cn.yj.sd.distributelock.db;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import javax.sql.DataSource;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 分布式锁使用示例
 * 演示三种MySQL分布式锁的使用方法
 * 
 * @author yaojun
 */
public class DistributedLockExample {

    private static DataSource dataSource;
    private static LockManager lockManager;

    public static void main(String[] args) {
        // 初始化数据源和锁管理器
        initDataSource();
        lockManager = new LockManager(dataSource);

        try {
            System.out.println("========== MySQL分布式锁示例 ==========\n");

            // 示例1：唯一索引锁
            System.out.println("【示例1】唯一索引锁");
            demonstrateUniqueKeyLock();

            // 示例2：悲观锁
            System.out.println("\n【示例2】悲观锁");
            demonstratePessimisticLock();

            // 示例3：乐观锁
            System.out.println("\n【示例3】乐观锁");
            demonstrateOptimisticLock();

            // 示例4：LockManager自动续期
            System.out.println("\n【示例4】自动续期机制");
            demonstrateAutoRenew();

            // 示例5：并发场景测试
            System.out.println("\n【示例5】并发场景测试");
            demonstrateConcurrentAccess();

        } finally {
            // 关闭资源
            lockManager.shutdown();
            if (dataSource instanceof HikariDataSource) {
                ((HikariDataSource) dataSource).close();
            }
        }
    }

    /**
     * 初始化数据源
     * 使用HikariCP连接池
     */
    private static void initDataSource() {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl("jdbc:mysql://localhost:3306/test_db?useSSL=false&serverTimezone=Asia/Shanghai");
        config.setUsername("root");
        config.setPassword("your_password");
        config.setMaximumPoolSize(10);
        config.setMinimumIdle(2);
        config.setConnectionTimeout(30000);
        config.setIdleTimeout(600000);
        config.setMaxLifetime(1800000);

        dataSource = new HikariDataSource(config);
    }

    /**
     * 示例1：唯一索引锁演示
     */
    private static void demonstrateUniqueKeyLock() {
        String lockName = "unique_key_lock_example";

        // 创建锁
        UniqueKeyDistributedLock lock = lockManager.createUniqueKeyLock(lockName, 30);

        try {
            // 尝试获取锁
            if (lock.tryLock()) {
                System.out.println("  ✓ 获取唯一索引锁成功");
                System.out.println("  → 执行业务逻辑...");

                // 模拟业务操作
                Thread.sleep(1000);

                // 支持重入
                if (lock.tryLock()) {
                    System.out.println("  ✓ 锁重入成功");
                    lock.unlock(); // 释放重入
                }

                // 锁续期
                boolean renewed = lock.renew(60);
                System.out.println("  ✓ 锁续期" + (renewed ? "成功" : "失败"));

            } else {
                System.out.println("  ✗ 获取锁失败");
            }
        } catch (Exception e) {
            System.err.println("  ✗ 错误: " + e.getMessage());
        } finally {
            // 释放锁
            if (lock.isHeldByCurrentHolder()) {
                lock.unlock();
                System.out.println("  ✓ 释放锁成功");
            }
        }
    }

    /**
     * 示例2：悲观锁演示
     */
    private static void demonstratePessimisticLock() {
        String lockName = "pessimistic_lock_example";

        // 创建悲观锁
        PessimisticDistributedLock lock = lockManager.createPessimisticLock(lockName, 30);

        try {
            // 使用executeWithLock简化锁操作
            String result = lock.executeWithLock(() -> {
                System.out.println("  ✓ 获取悲观锁成功（FOR UPDATE）");
                System.out.println("  → 在事务中执行业务逻辑...");

                // 模拟业务操作
                try {
                    Thread.sleep(500);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }

                return "业务执行完成";
            });

            System.out.println("  ✓ 执行结果: " + result);

        } catch (Exception e) {
            System.err.println("  ✗ 错误: " + e.getMessage());
        }
    }

    /**
     * 示例3：乐观锁演示
     */
    private static void demonstrateOptimisticLock() {
        String lockName = "optimistic_lock_example";

        // 创建乐观锁
        OptimisticDistributedLock lock = lockManager.createOptimisticLock(lockName, 30);

        try {
            // 尝试获取锁（会自动重试）
            boolean acquired = lock.tryLock(5, TimeUnit.SECONDS);

            if (acquired) {
                System.out.println("  ✓ 获取乐观锁成功，版本号: " + lock.getCurrentVersion());
                System.out.println("  → 执行业务逻辑...");

                // 模拟业务操作
                Thread.sleep(500);

                // 使用CAS续期
                boolean renewed = lock.renew(60);
                System.out.println("  ✓ CAS续期" + (renewed ? "成功，新版本号: " + lock.getCurrentVersion() : "失败"));

            } else {
                System.out.println("  ✗ 获取锁超时");
            }

        } catch (Exception e) {
            System.err.println("  ✗ 错误: " + e.getMessage());
        } finally {
            if (lock.isHeldByCurrentHolder()) {
                lock.unlock();
                System.out.println("  ✓ 释放锁成功");
            }
        }
    }

    /**
     * 示例4：自动续期机制演示
     */
    private static void demonstrateAutoRenew() {
        String lockName = "auto_renew_example";

        try {
            // 使用LockManager的自动续期功能
            String result = lockManager.executeWithLock(lockName, 10, () -> {
                System.out.println("  ✓ 获取锁成功，已启动自动续期");
                System.out.println("  → 执行长时间业务（模拟15秒）...");

                // 模拟长时间业务，超过锁的原始过期时间
                // 由于有自动续期，锁不会过期
                try {
                    for (int i = 1; i <= 3; i++) {
                        Thread.sleep(2000);
                        System.out.println("    - 已执行 " + (i * 2) + " 秒");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }

                return "长时间任务完成";
            });

            System.out.println("  ✓ 执行结果: " + result);

        } catch (Exception e) {
            System.err.println("  ✗ 错误: " + e.getMessage());
        }
    }

    /**
     * 示例5：并发场景测试
     */
    private static void demonstrateConcurrentAccess() {
        String lockName = "concurrent_lock_example";
        int threadCount = 5;

        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger counter = new AtomicInteger(0);

        System.out.println("  → 启动 " + threadCount + " 个线程竞争同一把锁");

        for (int i = 0; i < threadCount; i++) {
            final int threadId = i + 1;
            executor.submit(() -> {
                try {
                    UniqueKeyDistributedLock lock = lockManager.createUniqueKeyLock(lockName, 30);

                    // 带超时的获取锁
                    if (lock.lock(10, TimeUnit.SECONDS)) {
                        try {
                            successCount.incrementAndGet();
                            int value = counter.incrementAndGet();
                            System.out.println("    线程-" + threadId + " 获取锁成功，计数器: " + value);
                            Thread.sleep(500); // 模拟业务
                        } finally {
                            lock.unlock();
                        }
                    } else {
                        System.out.println("    线程-" + threadId + " 获取锁超时");
                    }
                } catch (Exception e) {
                    System.err.println("    线程-" + threadId + " 错误: " + e.getMessage());
                } finally {
                    latch.countDown();
                }
            });
        }

        try {
            latch.await(30, TimeUnit.SECONDS);
            executor.shutdown();

            System.out.println("  ✓ 并发测试完成");
            System.out.println("    - 成功获取锁次数: " + successCount.get());
            System.out.println("    - 最终计数器值: " + counter.get());

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
