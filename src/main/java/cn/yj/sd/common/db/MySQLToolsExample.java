package cn.yj.sd.common.db;

import javax.sql.DataSource;
import java.util.List;
import java.util.Map;

/**
 * MySQL数据库工具使用示例
 * 演示如何使用JdbcTemplate、TransactionTemplate等工具类
 *
 * @author yaojun
 */
public class MySQLToolsExample {

    public static void main(String[] args) {
        try {
            // 1. 初始化数据源
            initDataSource();

            // 2. 演示基本CRUD操作
            demonstrateCRUD();

            // 3. 演示事务操作
            demonstrateTransaction();

            // 4. 演示嵌套事务
            demonstrateNestedTransaction();

            // 5. 显示连接池状态
            showPoolStats();

        } finally {
            // 关闭数据源
            MySQLDataSourceManager.shutdownAll();
            System.out.println("\n✓ 所有数据源已关闭");
        }
    }

    /**
     * 方式一：从配置文件初始化
     */
    private static void initDataSource() {
        System.out.println("========== 初始化数据源 ==========\n");

        // 方式一：从properties文件加载配置
        // MySQLConfig config = MySQLConfig.fromProperties("mysql.properties");

        // 方式二：使用Builder手动构建配置
        MySQLConfig config = new MySQLConfig.Builder()
                .jdbcUrl("jdbc:mysql://localhost:3306/test_db?useSSL=false&serverTimezone=Asia/Shanghai")
                .username("root")
                .password("your_password")
                .maxPoolSize(10)
                .minIdle(2)
                .poolName("ExamplePool")
                .build();

        // 初始化数据源
        DataSource dataSource = MySQLDataSourceManager.initialize(config);
        System.out.println("✓ 数据源初始化成功");
        System.out.println("  配置: " + config);
    }

    /**
     * 演示基本CRUD操作
     */
    private static void demonstrateCRUD() {
        System.out.println("\n========== CRUD操作示例 ==========\n");

        JdbcTemplate jdbc = new JdbcTemplate();

        // 1. 创建表
        System.out.println("【创建表】");
        jdbc.update("""
                    CREATE TABLE IF NOT EXISTS users (
                        id BIGINT AUTO_INCREMENT PRIMARY KEY,
                        name VARCHAR(100) NOT NULL,
                        email VARCHAR(200),
                        age INT DEFAULT 0,
                        created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                        updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
                    )
                """);
        System.out.println("  ✓ 表创建成功");

        // 2. 插入数据
        System.out.println("\n【插入数据】");
        long userId = jdbc.insertAndReturnKey(
                "INSERT INTO users (name, email, age) VALUES (?, ?, ?)",
                "张三", "zhangsan@example.com", 25);
        System.out.println("  ✓ 插入成功，生成ID: " + userId);

        // 批量插入
        jdbc.update("INSERT INTO users (name, email, age) VALUES (?, ?, ?)", "李四", "lisi@example.com", 30);
        jdbc.update("INSERT INTO users (name, email, age) VALUES (?, ?, ?)", "王五", "wangwu@example.com", 28);
        System.out.println("  ✓ 批量插入完成");

        // 3. 查询单个对象
        System.out.println("\n【查询单个对象】");
        User user = jdbc.queryForObject(
                "SELECT * FROM users WHERE id = ?",
                (rs, rowNum) -> new User(
                        rs.getLong("id"),
                        rs.getString("name"),
                        rs.getString("email"),
                        rs.getInt("age")),
                userId);
        System.out.println("  ✓ 查询结果: " + user);

        // 4. 查询列表
        System.out.println("\n【查询列表】");
        List<User> users = jdbc.query(
                "SELECT * FROM users WHERE age >= ?",
                (rs, rowNum) -> new User(
                        rs.getLong("id"),
                        rs.getString("name"),
                        rs.getString("email"),
                        rs.getInt("age")),
                25);
        System.out.println("  ✓ 查询到 " + users.size() + " 条记录:");
        users.forEach(u -> System.out.println("    - " + u));

        // 5. 查询为Map
        System.out.println("\n【查询为Map】");
        Map<String, Object> userMap = jdbc.queryForMap("SELECT * FROM users WHERE id = ?", userId);
        System.out.println("  ✓ 结果Map: " + userMap);

        // 6. 聚合查询
        System.out.println("\n【聚合查询】");
        Long count = jdbc.queryForScalar("SELECT COUNT(*) FROM users", Long.class);
        Double avgAge = jdbc.queryForScalar("SELECT AVG(age) FROM users", Double.class);
        System.out.println("  ✓ 总数: " + count + ", 平均年龄: " + avgAge);

        // 7. 更新数据
        System.out.println("\n【更新数据】");
        int updateCount = jdbc.update("UPDATE users SET age = ? WHERE name = ?", 26, "张三");
        System.out.println("  ✓ 更新了 " + updateCount + " 条记录");

        // 8. 使用SqlUtils构建查询
        System.out.println("\n【模糊查询】");
        List<User> likeUsers = jdbc.query(
                "SELECT * FROM users WHERE name LIKE ?",
                (rs, rowNum) -> new User(
                        rs.getLong("id"),
                        rs.getString("name"),
                        rs.getString("email"),
                        rs.getInt("age")),
                SqlUtils.likePrefix("张"));
        System.out.println("  ✓ 模糊查询结果: " + likeUsers);

        // 9. 删除数据
        System.out.println("\n【删除数据】");
        int deleteCount = jdbc.update("DELETE FROM users WHERE name = ?", "王五");
        System.out.println("  ✓ 删除了 " + deleteCount + " 条记录");
    }

    /**
     * 演示事务操作
     */
    private static void demonstrateTransaction() {
        System.out.println("\n========== 事务操作示例 ==========\n");

        TransactionTemplate tx = new TransactionTemplate();
        JdbcTemplate jdbc = tx.getJdbcTemplate();

        // 1. 成功的事务
        System.out.println("【成功事务】");
        try {
            String result = tx.execute(conn -> {
                jdbc.update(conn, "INSERT INTO users (name, email, age) VALUES (?, ?, ?)",
                        "事务用户1", "tx1@example.com", 35);
                jdbc.update(conn, "INSERT INTO users (name, email, age) VALUES (?, ?, ?)",
                        "事务用户2", "tx2@example.com", 40);
                return "插入了2个用户";
            });
            System.out.println("  ✓ 事务成功: " + result);
        } catch (Exception e) {
            System.out.println("  ✗ 事务失败: " + e.getMessage());
        }

        // 2. 失败回滚的事务
        System.out.println("\n【失败回滚事务】");
        try {
            tx.executeWithoutResult(conn -> {
                jdbc.update(conn, "INSERT INTO users (name, email, age) VALUES (?, ?, ?)",
                        "回滚用户", "rollback@example.com", 50);
                // 模拟异常
                if (true) {
                    throw new RuntimeException("模拟业务异常");
                }
            });
        } catch (Exception e) {
            System.out.println("  ✓ 事务已回滚: " + e.getMessage());
        }

        // 验证回滚
        Long count = jdbc.queryForScalar("SELECT COUNT(*) FROM users WHERE name = ?", Long.class, "回滚用户");
        System.out.println("  ✓ 验证回滚结果，'回滚用户'数量: " + count);

        // 3. 指定隔离级别
        System.out.println("\n【指定隔离级别事务】");
        tx.execute(conn -> {
            System.out.println("  → 使用 REPEATABLE_READ 隔离级别");
            jdbc.update(conn, "UPDATE users SET age = age + 1 WHERE name = ?", "张三");
            return null;
        }, TransactionTemplate.IsolationLevel.REPEATABLE_READ);
        System.out.println("  ✓ 事务完成");
    }

    /**
     * 演示嵌套事务
     */
    private static void demonstrateNestedTransaction() {
        System.out.println("\n========== 嵌套事务示例 ==========\n");

        TransactionTemplate tx = new TransactionTemplate();
        JdbcTemplate jdbc = tx.getJdbcTemplate();

        try {
            tx.executeWithoutResult(outerConn -> {
                System.out.println("  外层事务开始，深度: " + TransactionTemplate.getTransactionDepth());
                jdbc.update(outerConn, "INSERT INTO users (name, email, age) VALUES (?, ?, ?)",
                        "外层用户", "outer@example.com", 55);

                try {
                    // 嵌套事务
                    tx.executeWithoutResult(innerConn -> {
                        System.out.println("  内层事务开始，深度: " + TransactionTemplate.getTransactionDepth());
                        jdbc.update(innerConn, "INSERT INTO users (name, email, age) VALUES (?, ?, ?)",
                                "内层用户", "inner@example.com", 60);

                        // 内层事务失败
                        throw new RuntimeException("内层事务失败");
                    });
                } catch (Exception e) {
                    System.out.println("  内层事务回滚: " + e.getMessage());
                    // 外层事务可以继续
                }

                System.out.println("  回到外层事务，深度: " + TransactionTemplate.getTransactionDepth());
            });
            System.out.println("  ✓ 外层事务提交成功");
        } catch (Exception e) {
            System.out.println("  ✗ 外层事务失败: " + e.getMessage());
        }

        // 验证结果
        Long outerCount = jdbc.queryForScalar("SELECT COUNT(*) FROM users WHERE name = ?", Long.class, "外层用户");
        Long innerCount = jdbc.queryForScalar("SELECT COUNT(*) FROM users WHERE name = ?", Long.class, "内层用户");
        System.out.println("  验证：外层用户=" + outerCount + "，内层用户=" + innerCount);
    }

    /**
     * 显示连接池统计信息
     */
    private static void showPoolStats() {
        System.out.println("\n========== 连接池状态 ==========\n");

        MySQLDataSourceManager.PoolStats stats = MySQLDataSourceManager.getPoolStats();
        if (stats != null) {
            System.out.println("  " + stats);
        }
    }

    /**
     * 简单的User类用于演示
     */
    static class User {
        private final Long id;
        private final String name;
        private final String email;
        private final Integer age;

        public User(Long id, String name, String email, Integer age) {
            this.id = id;
            this.name = name;
            this.email = email;
            this.age = age;
        }

        @Override
        public String toString() {
            return "User{id=" + id + ", name='" + name + "', email='" + email + "', age=" + age + "}";
        }
    }
}
