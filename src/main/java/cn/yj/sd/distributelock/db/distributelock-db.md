# 基于MySQL的分布式锁实现

## 一、设计概述

本模块实现了基于MySQL数据库的分布式锁，提供了三种不同的实现方式，适用于不同的业务场景。

### 三种实现方式对比

| 实现方式 | 原理 | 优点 | 缺点 | 适用场景 |
|---------|------|------|------|---------|
| **唯一索引锁** | INSERT冲突检测 | 实现简单、易理解 | 性能一般、需轮询 | 通用场景 |
| **悲观锁** | SELECT FOR UPDATE | 可靠性高、自动释放 | 占用连接、性能较低 | 事务场景 |
| **乐观锁** | 版本号CAS | 无锁竞争、性能好 | 冲突需重试 | 读多写少 |

## 二、架构设计

```
┌─────────────────────────────────────────────────────────────┐
│                    DistributedLock (接口)                    │
│  定义分布式锁的核心操作规范                                    │
└─────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────┐
│                AbstractDistributedLock (抽象类)              │
│  提供通用的锁操作模板和工具方法                                │
└─────────────────────────────────────────────────────────────┘
                              │
        ┌─────────────────────┼─────────────────────┐
        ▼                     ▼                     ▼
┌───────────────┐    ┌───────────────┐    ┌───────────────┐
│UniqueKeyDist- │    │Pessimistic-   │    │Optimistic-    │
│ributedLock    │    │DistributedLock│    │DistributedLock│
│   唯一索引锁   │    │    悲观锁     │    │    乐观锁     │
└───────────────┘    └───────────────┘    └───────────────┘
                              │
                              ▼
                    ┌─────────────────┐
                    │   LockManager   │
                    │ 锁管理器(续期/清理)│
                    └─────────────────┘
```

## 三、类文件说明

> **包路径：** `cn.yj.sd.distributelock.db`

| 文件名 | 说明 |
|-------|------|
| `DistributedLock.java` | 分布式锁接口，定义核心操作 |
| `AbstractDistributedLock.java` | 抽象基类，提供通用功能 |
| `UniqueKeyDistributedLock.java` | 唯一索引锁实现 |
| `PessimisticDistributedLock.java` | 悲观锁实现 |
| `OptimisticDistributedLock.java` | 乐观锁实现 |
| `LockManager.java` | 锁管理器（续期、清理） |
| `LockRepository.java` | 数据访问层 |
| `LockInfo.java` | 锁信息实体类 |
| `LockException.java` | 锁异常类 |
| `DistributedLockExample.java` | 使用示例 |
| `schema.sql` | 数据库DDL |

## 四、数据库表结构

```sql
CREATE TABLE distributed_lock (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    lock_name VARCHAR(128) NOT NULL COMMENT '锁名称',
    lock_value VARCHAR(128) NOT NULL COMMENT '锁值(持有者标识)',
    expire_time DATETIME NOT NULL COMMENT '过期时间',
    version INT DEFAULT 0 COMMENT '版本号(乐观锁用)',
    reentrant_count INT DEFAULT 1 COMMENT '重入次数',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_lock_name (lock_name),
    KEY idx_expire_time (expire_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
```

## 五、实现详解

### 5.1 唯一索引锁 (UniqueKeyDistributedLock)

**核心原理：** 利用数据库唯一索引的特性，插入成功即获取锁。

```java
// 获取锁
INSERT INTO distributed_lock (lock_name, lock_value, expire_time) 
VALUES (?, ?, ?);

// 释放锁
DELETE FROM distributed_lock 
WHERE lock_name = ? AND lock_value = ?;
```

**特点：**
- ✅ 实现简单
- ✅ 支持重入
- ✅ 支持续期
- ❌ 需要轮询获取锁

### 5.2 悲观锁 (PessimisticDistributedLock)

**核心原理：** 使用 `SELECT ... FOR UPDATE` 获取行级锁。

```java
// 开启事务
BEGIN;

// 获取行锁
SELECT * FROM distributed_lock WHERE lock_name = ? FOR UPDATE;

// 更新持有者
UPDATE distributed_lock SET lock_value = ? WHERE lock_name = ?;

// 释放锁
COMMIT;
```

**特点：**
- ✅ 可靠性高
- ✅ 事务自动释放
- ❌ 占用数据库连接
- ❌ 性能较低

### 5.3 乐观锁 (OptimisticDistributedLock)

**核心原理：** 使用版本号进行CAS更新。

```java
// 查询当前版本
SELECT version FROM distributed_lock WHERE lock_name = ?;

// CAS更新
UPDATE distributed_lock 
SET lock_value = ?, version = version + 1 
WHERE lock_name = ? AND version = ?;
```

**特点：**
- ✅ 无锁竞争
- ✅ 性能较好
- ❌ 高并发时冲突多
- ❌ 需要重试机制

## 六、高级特性

### 6.1 锁续期（Watchdog机制）

```java
LockManager lockManager = new LockManager(dataSource);

// 自动续期
lockManager.executeWithLock("my_lock", 30, () -> {
    // 执行长时间业务
    // 锁会自动续期，不会过期
    return result;
});
```

### 6.2 可重入锁

```java
UniqueKeyDistributedLock lock = lockManager.createUniqueKeyLock("my_lock");

lock.tryLock();  // 第一次获取
lock.tryLock();  // 重入成功，reentrant_count = 2

lock.unlock();   // reentrant_count = 1
lock.unlock();   // 完全释放
```

### 6.3 过期锁清理

```java
// 手动清理
int cleaned = lockManager.cleanExpiredLocks();

// 自动清理（LockManager内部定时任务）
// 默认每60秒清理一次过期锁
```

## 七、使用示例

```java
// 1. 创建LockManager
LockManager lockManager = new LockManager(dataSource);

// 2. 创建并使用唯一索引锁
UniqueKeyDistributedLock lock = lockManager.createUniqueKeyLock("order:create:123");
try {
    if (lock.tryLock()) {
        // 执行业务逻辑
        createOrder();
    }
} finally {
    if (lock.isHeldByCurrentHolder()) {
        lock.unlock();
    }
}

// 3. 使用LockManager简化操作（带自动续期）
String result = lockManager.executeWithLock("order:create:123", () -> {
    return createOrder();
});

// 4. 关闭资源
lockManager.shutdown();
```

## 八、选型建议

| 场景 | 推荐方案 | 原因 |
|-----|---------|------|
| 通用分布式锁 | 唯一索引锁 | 简单可靠，适合大多数场景 |
| 数据库事务中 | 悲观锁 | 与事务绑定，可靠性高 |
| 读多写少 | 乐观锁 | 无锁竞争，性能最好 |
| 高并发写入 | 考虑Redis | MySQL性能可能成为瓶颈 |

## 九、注意事项

1. **锁名称规范**：建议使用 `业务:类型:ID` 的格式，如 `order:create:12345`
2. **过期时间设置**：根据业务执行时间合理设置，建议开启自动续期
3. **异常处理**：务必在 finally 中释放锁，避免死锁
4. **性能考虑**：高并发场景建议使用Redis分布式锁
5. **数据库性能**：确保 `lock_name` 字段有索引