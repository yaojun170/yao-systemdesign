# Redis 分布式锁设计文档

## 1. 概述

### 1.1 背景

在分布式系统中，多个服务实例可能同时访问共享资源，需要一种机制来保证同一时刻只有一个客户端能够操作该资源。分布式锁是解决这一问题的常用方案。

### 1.2 为什么选择 Redis

| 方案 | 优点 | 缺点 |
|------|------|------|
| **Redis** | 性能高、实现简单、支持过期时间 | 主从切换时可能丢失锁 |
| MySQL | 强一致性 | 性能较低、需要维护表 |
| ZooKeeper | 强一致性、可靠性高 | 性能相对较低、运维复杂 |
| etcd | 强一致性、云原生 | 生态不如Redis成熟 |

Redis 分布式锁适用于对一致性要求不是极端严格、但对性能要求较高的场景。

### 1.3 设计目标

1. **互斥性**：同一时刻只有一个客户端持有锁
2. **防死锁**：持有锁的客户端崩溃后，锁能自动释放
3. **安全性**：锁只能由持有者释放，不能被其他客户端误删
4. **可重入**：同一线程可多次获取同一把锁
5. **自动续期**：防止业务执行超时导致锁提前释放
6. **高可用**：Redis 故障时的容错处理

---

## 2. 核心设计

### 2.1 加锁机制

#### 2.1.1 命令选择

使用 Redis 的 `SET` 命令配合参数实现原子性加锁：

```
SET lock_key lock_value NX PX expire_ms
```

| 参数 | 含义 |
|------|------|
| `NX` | Only set the key if it does Not eXist（互斥性保证） |
| `PX` | 设置过期时间（毫秒），防止死锁 |

#### 2.1.2 锁值设计

锁值（lock_value）需要全局唯一，用于标识锁的持有者：

```
lock_value = UUID + ":" + ThreadId
```

- **UUID**：确保不同 JVM 实例间的唯一性
- **ThreadId**：确保同一 JVM 内不同线程的唯一性

#### 2.1.3 加锁流程

```
┌─────────────────────────────────────────────────────────────┐
│                         加锁流程                              │
├─────────────────────────────────────────────────────────────┤
│                                                             │
│  ┌─────────┐    是     ┌───────────┐                        │
│  │ 检查重入 │─────────▶│ 重入计数+1 │──────▶ 返回成功         │
│  └────┬────┘          └───────────┘                        │
│       │ 否                                                  │
│       ▼                                                     │
│  ┌───────────────────────────────────┐                     │
│  │ SET key value NX PX expire_ms     │                     │
│  └───────────────┬───────────────────┘                     │
│                  │                                          │
│        ┌─────────┴─────────┐                               │
│        ▼                   ▼                                │
│    成功(OK)            失败(nil)                            │
│        │                   │                                │
│        ▼                   ▼                                │
│  ┌───────────┐      ┌───────────┐                          │
│  │ 初始化计数 │      │ 返回失败   │                          │
│  │ 启动看门狗 │      └───────────┘                          │
│  └─────┬─────┘                                              │
│        ▼                                                    │
│   返回成功                                                   │
│                                                             │
└─────────────────────────────────────────────────────────────┘
```

### 2.2 解锁机制

#### 2.2.1 安全释放问题

直接使用 `DEL` 命令释放锁存在风险：

```
时间线:
T1: Client A 获取锁，设置过期时间 30s
T2: Client A 业务执行时间过长（如 35s）
T3: 锁自动过期释放
T4: Client B 获取锁
T5: Client A 业务完成，执行 DEL 删除锁
    ❌ 此时删除的是 Client B 的锁！
```

#### 2.2.2 Lua 脚本保证原子性

使用 Lua 脚本实现"检查并删除"的原子操作：

```lua
-- 释放锁的 Lua 脚本
if redis.call('get', KEYS[1]) == ARGV[1] then
    return redis.call('del', KEYS[1])
else
    return 0
end
```

- 只有当锁值匹配时才执行删除
- Lua 脚本在 Redis 中原子执行，不会被其他命令打断
- Q:为什么释放锁需要使用lua脚本？按理说释放锁的进程只有一个也就说获取锁的进程，不存在并发问题
	- 进程A持有锁，打算释放锁，执行get key检查锁的值是不是自己
	- 进程A判断完毕后但未执行del之前，锁过期了
	- 客户端B获取到锁，并设置了新的值
	- 客户端A执行del，误删了客户端B的锁

#### 2.2.3 解锁流程

```
┌─────────────────────────────────────────────────────────────┐
│                         解锁流程                              │
├─────────────────────────────────────────────────────────────┤
│                                                             │
│  ┌────────────────┐                                         │
│  │ 获取重入计数     │                                         │
│  └───────┬────────┘                                         │
│          │                                                  │
│          ▼                                                  │
│    ┌───────────┐     是                                     │
│    │ 计数 > 1？ │─────────▶ 计数-1 ──▶ 返回成功              │
│    └─────┬─────┘                                            │
│          │ 否                                                │
│          ▼                                                  │
│  ┌────────────────┐                                         │
│  │ 停止看门狗       │                                         │
│  └───────┬────────┘                                         │
│          ▼                                                  │
│  ┌─────────────────────────────────────┐                   │
│  │ 执行 Lua 脚本（检查并删除）            │                   │
│  └───────────────┬─────────────────────┘                   │
│                  │                                          │
│        ┌─────────┴─────────┐                               │
│        ▼                   ▼                                │
│    返回 1 (成功)        返回 0 (失败)                        │
│                                                             │
└─────────────────────────────────────────────────────────────┘
```

### 2.3 可重入设计

#### 2.3.1 问题场景

```java
public void methodA() {
    lock.lock();
    try {
        methodB();  // 调用 methodB，也需要获取同一把锁
    } finally {
        lock.unlock();
    }
}

public void methodB() {
    lock.lock();  // 如果不支持重入，这里会死锁！
    try {
        // ...
    } finally {
        lock.unlock();
    }
}
```

#### 2.3.2 实现方案

使用本地 `ConcurrentHashMap` 记录每个线程的重入次数：

```java
// 锁标识 -> 重入次数
Map<String, AtomicInteger> REENTRANT_COUNT = new ConcurrentHashMap<>();

// 锁标识 = lockKey + ":" + lockValue
String threadLockKey = lockKey + ":" + lockValue;
```

- **加锁时**：先检查重入计数，若 > 0 则直接增加计数；否则执行 Redis 加锁
- **解锁时**：先减少计数，若 > 0 则不释放 Redis 锁；若 = 0 则执行 Lua 脚本释放

### 2.4 看门狗(Watchdog)机制

#### 2.4.1 问题场景

```
问题：业务执行时间超过锁的过期时间

时间线:
T0:  获取锁，过期时间 30s
T30: 锁自动过期
T35: 业务还在执行中，但锁已经被其他客户端获取！
```

#### 2.4.2 解决方案

后台定时任务定期续期锁：

```
续期间隔 = 过期时间 / 3
```

例如：过期时间 30s，则每 10s 续期一次，将过期时间重置为 30s。

#### 2.4.3 续期 Lua 脚本

```lua
-- 续期锁的 Lua 脚本
if redis.call('get', KEYS[1]) == ARGV[1] then
    return redis.call('pexpire', KEYS[1], ARGV[2])
else
    return 0
end
```

- 只有锁值匹配时才续期，防止续期已被其他客户端持有的锁

#### 2.4.4 看门狗生命周期

```
┌──────────────────────────────────────────────────────────┐
│                    看门狗生命周期                          │
├──────────────────────────────────────────────────────────┤
│                                                          │
│   加锁成功                                                │
│      │                                                   │
│      ▼                                                   │
│  ┌────────────────┐                                      │
│  │ 启动定时任务     │                                      │
│  │ 间隔: expire/3  │                                      │
│  └───────┬────────┘                                      │
│          │                                               │
│          ▼                                               │
│  ┌────────────────┐    续期成功                          │
│  │ 执行续期 Lua    │──────────────────┐                  │
│  └───────┬────────┘                  │                  │
│          │                           │                  │
│          │ 续期失败                   │                  │
│          │ (锁已不属于自己)            │                  │
│          ▼                           │                  │
│  ┌────────────────┐                  │                  │
│  │ 停止看门狗      │◀─────解锁时─────────┘                  │
│  └────────────────┘                                      │
│                                                          │
└──────────────────────────────────────────────────────────┘
```

---

## 3. 类设计

### 3.1 类图

```
┌─────────────────────────────────────────────────────────────┐
│                      <<interface>>                          │
│                     DistributedLock                         │
├─────────────────────────────────────────────────────────────┤
│ + tryLock(): boolean                                        │
│ + tryLock(waitTime, unit): boolean                          │
│ + unlock(): boolean                                         │
│ + isHeldByCurrentThread(): boolean                          │
│ + getLockName(): String                                     │
└──────────────────────────┬──────────────────────────────────┘
                           │ implements
                           ▼
┌─────────────────────────────────────────────────────────────┐
│                  RedisDistributedLock                       │
├─────────────────────────────────────────────────────────────┤
│ - LOCK_PREFIX: String = "distributed_lock:"                 │
│ - DEFAULT_EXPIRE_MS: long = 30000                           │
│ - UNLOCK_SCRIPT: String                                     │
│ - RENEW_SCRIPT: String                                      │
│ - WATCHDOG_SCHEDULER: ScheduledExecutorService              │
│ - REENTRANT_COUNT: Map<String, AtomicInteger>               │
├─────────────────────────────────────────────────────────────┤
│ - jedisPool: JedisPool                                      │
│ - lockName: String                                          │
│ - lockKey: String                                           │
│ - lockValue: String                                         │
│ - expireMs: long                                            │
│ - enableWatchdog: boolean                                   │
│ - watchdogFuture: ScheduledFuture<?>                        │
├─────────────────────────────────────────────────────────────┤
│ + RedisDistributedLock(lockName)                            │
│ + RedisDistributedLock(lockName, expireMs)                  │
│ + RedisDistributedLock(lockName, expireMs, enableWatchdog)  │
│ + RedisDistributedLock(jedisPool, lockName, expireMs, ...)  │
│ + tryLock(): boolean                                        │
│ + tryLock(waitTime, unit): boolean                          │
│ + unlock(): boolean                                         │
│ + isHeldByCurrentThread(): boolean                          │
│ - getThreadLockKey(): String                                │
│ - startWatchdog(): void                                     │
│ - stopWatchdog(): void                                      │
│ - renewLock(): void                                         │
└─────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────┐
│                      JedisConfig                            │
├─────────────────────────────────────────────────────────────┤
│ - jedisPool: JedisPool (volatile)                           │
│ - DEFAULT_HOST: String = "localhost"                        │
│ - DEFAULT_PORT: int = 6379                                  │
│ - DEFAULT_TIMEOUT: int = 2000                               │
│ - DEFAULT_MAX_TOTAL: int = 50                               │
├─────────────────────────────────────────────────────────────┤
│ + getJedisPool(): JedisPool                                 │
│ + init(host, port, password): void                          │
│ + init(host, port, password, timeout): void                 │
│ + close(): void                                             │
│ - createJedisPool(...): JedisPool                           │
└─────────────────────────────────────────────────────────────┘
```

### 3.2 文件说明

| 文件 | 职责 |
|------|------|
| `DistributedLock.java` | 分布式锁接口定义 |
| `JedisConfig.java` | Jedis 连接池配置与管理（单例） |
| `RedisDistributedLock.java` | 核心锁实现 |
| `RedisLockExample.java` | 使用示例 |

---

## 4. 使用指南

### 4.1 初始化连接

```java
// 使用默认配置 (localhost:6379)
JedisConfig.getJedisPool();

// 或自定义配置
JedisConfig.init("redis.example.com", 6379, "password");
```

### 4.2 基本使用

```java
RedisDistributedLock lock = new RedisDistributedLock("order:12345");

if (lock.tryLock()) {
    try {
        // 执行业务逻辑
        processOrder();
    } finally {
        lock.unlock();
    }
} else {
    // 获取锁失败
    throw new RuntimeException("获取锁失败");
}
```

### 4.3 带超时等待

```java
RedisDistributedLock lock = new RedisDistributedLock("inventory:sku001");

// 最多等待 5 秒
if (lock.tryLock(5, TimeUnit.SECONDS)) {
    try {
        deductInventory();
    } finally {
        lock.unlock();
    }
} else {
    // 超时
    throw new RuntimeException("获取锁超时");
}
```

### 4.4 自定义过期时间

```java
// 锁过期时间 10 秒，启用看门狗
RedisDistributedLock lock = new RedisDistributedLock("task:cleanup", 10000, true);

// 锁过期时间 60 秒，禁用看门狗（业务时间可控时）
RedisDistributedLock lock2 = new RedisDistributedLock("task:report", 60000, false);
```

### 4.5 可重入使用

```java
RedisDistributedLock lock = new RedisDistributedLock("account:transfer");

public void transfer() {
    if (lock.tryLock()) {
        try {
            validate();  // 内部也需要同一把锁
            execute();
        } finally {
            lock.unlock();
        }
    }
}

private void validate() {
    if (lock.tryLock()) {  // 可重入，不会死锁
        try {
            // 校验逻辑
        } finally {
            lock.unlock();
        }
    }
}
```

---

## 5. 注意事项

### 5.1 锁过期时间设置

| 场景 | 建议 |
|------|------|
| 业务时间可预估且较短 | 过期时间 = 预估时间 × 2，可以禁用看门狗 |
| 业务时间不可预估 | 使用默认 30s + 启用看门狗 |
| 长时间任务 | 必须启用看门狗 |

### 5.2 异常处理

```java
try {
    if (lock.tryLock(5, TimeUnit.SECONDS)) {
        try {
            // 业务逻辑
        } finally {
            lock.unlock();  // 确保释放锁
        }
    }
} catch (Exception e) {
    // 记录日志，处理异常
    log.error("分布式锁操作失败", e);
}
```

### 5.3 锁名称规范

建议采用 `业务模块:资源类型:资源ID` 的格式：

```java
// 订单锁
"order:pay:12345"

// 库存锁
"inventory:deduct:SKU001"

// 用户锁
"user:register:phone_13800138000"
```

### 5.4 不适用场景

1. **强一致性要求**：Redis 主从切换时可能丢失锁，需要使用 ZooKeeper 或 etcd
2. **长时间锁定**：超过几分钟的任务应考虑其他方案
3. **高频竞争**：大量客户端频繁竞争同一把锁时，应考虑优化业务设计

---

## 6. 高可用方案

### 6.1 单点 Redis 的问题

```
问题场景：
1. Client A 从 Master 获取锁
2. Master 宕机，锁未同步到 Slave
3. Slave 提升为新 Master
4. Client B 从新 Master 获取同一把锁
5. 此时 Client A 和 B 都认为自己持有锁！
```

### 6.2 RedLock 算法（可选扩展）

对于需要更高可靠性的场景，可以考虑实现 RedLock 算法：

1. 获取当前时间戳
2. 依次向 N 个 Redis 节点请求加锁（N 通常为 5）
3. 如果获取锁的节点数 >= N/2 + 1，且总耗时 < 锁过期时间，则加锁成功
4. 如果失败，向所有节点发送解锁请求

> ⚠️ 注意：RedLock 存在争议，Martin Kleppmann 指出其存在安全性问题。生产环境中需要根据具体场景权衡。

### 6.3 建议

| 场景 | 推荐方案 |
|------|----------|
| 一般业务 | 单节点 Redis + 看门狗 |
| 较高可靠性 | Redis Sentinel 或 Cluster |
| 强一致性 | ZooKeeper / etcd |

---

## 7. 性能考虑

### 7.1 连接池配置

```java
JedisPoolConfig poolConfig = new JedisPoolConfig();
poolConfig.setMaxTotal(50);      // 最大连接数
poolConfig.setMaxIdle(10);       // 最大空闲连接
poolConfig.setMinIdle(5);        // 最小空闲连接
poolConfig.setTestOnBorrow(true); // 借用时测试连接有效性
```

### 7.2 重试策略

- 重试间隔：100ms
- 避免过于频繁的重试对 Redis 造成压力
- 可根据业务需求调整重试间隔

### 7.3 看门狗开销

- 看门狗使用共享的 `ScheduledExecutorService`（单线程）
- 续期操作轻量，不会对 Redis 造成明显压力
- 客户端崩溃时看门狗自动停止（守护线程）

---

## 8. 与 Redisson 对比

| 特性 | 本实现 | Redisson |
|------|--------|----------|
| 依赖 | 仅 Jedis | 独立库 |
| 复杂度 | 简单 | 功能丰富但复杂 |
| 可重入 | ✅ | ✅ |
| 看门狗 | ✅ | ✅ |
| RedLock | ❌ | ✅ |
| 公平锁 | ❌ | ✅ |
| 读写锁 | ❌ | ✅ |
| 信号量 | ❌ | ✅ |
| 适用场景 | 简单分布式锁 | 复杂分布式协调 |

---

## 9. 总结

本实现提供了一个**简单、安全、生产可用**的 Redis 分布式锁解决方案：

- ✅ **原子性加锁**：`SET NX PX` 一条命令完成
- ✅ **安全释放**：Lua 脚本保证只删除自己的锁
- ✅ **可重入**：本地计数器支持重入
- ✅ **自动续期**：看门狗防止锁过期
- ✅ **轻量依赖**：仅依赖 Jedis

适用于大多数需要分布式锁的业务场景，对于需要更复杂功能的场景可以考虑使用 Redisson。
