# ZooKeeper 分布式锁实现

## 1. 概述

本文档详细介绍基于原生 ZooKeeper API 实现的分布式锁方案，不依赖 Curator 等第三方库。

## 2. 设计原理

### 2.1 临时顺序节点（EPHEMERAL_SEQUENTIAL）

ZooKeeper 分布式锁的核心是利用**临时顺序节点**的特性：

- **临时性（EPHEMERAL）**：节点会在客户端会话结束时自动删除，防止锁死锁
- **顺序性（SEQUENTIAL）**：节点名自动追加递增序号，保证获取锁的顺序

### 2.2 锁获取流程

```
    ┌─────────────────────────────────────────────────────────────┐
    │                    锁获取流程                                │
    └─────────────────────────────────────────────────────────────┘
                                │
                                ▼
                    ┌───────────────────────┐
                    │ 创建临时顺序节点       │
                    │ /locks/lock-0000001   │
                    └───────────────────────┘
                                │
                                ▼
                    ┌───────────────────────┐
                    │ 获取所有子节点并排序    │
                    └───────────────────────┘
                                │
                                ▼
                    ┌───────────────────────┐
                    │ 判断当前节点是否最小    │
                    └───────────────────────┘
                         │           │
                        YES         NO
                         │           │
                         ▼           ▼
            ┌─────────────────┐  ┌─────────────────────┐
            │   获取锁成功     │  │ 监听前一个节点删除   │
            └─────────────────┘  └─────────────────────┘
                                            │
                                            ▼
                                 ┌───────────────────────┐
                                 │ 等待通知后重新判断     │
                                 └───────────────────────┘
```

### 2.3 避免惊群效应

传统做法：所有等待的客户端都监听锁节点
- 问题：锁释放时，所有客户端同时被唤醒，造成惊群效应

优化做法：每个客户端只监听前一个节点
- 优点：锁释放时，只唤醒下一个等待者

```
节点队列：lock-001 → lock-002 → lock-003 → lock-004
            │           │          │          │
           持锁        监听       监听       监听
                      001        002        003
```

## 3. 核心类说明

### 3.1 DistributedLock 接口

```java
public interface DistributedLock {
    void lock() throws Exception;                           // 阻塞获取锁
    boolean tryLock() throws Exception;                     // 尝试获取锁
    boolean tryLock(long timeout, TimeUnit unit);           // 超时获取锁
    void unlock() throws Exception;                         // 释放锁
    boolean isHeldByCurrentThread();                        // 是否持有锁
}
```

### 3.2 ZkDistributedLock

基于临时顺序节点的公平分布式锁：

| 特性 | 说明 |
|------|------|
| 公平性 | 按照请求顺序获取锁 |
| 可重入 | 同一线程可多次获取 |
| 超时支持 | 支持超时获取锁 |
| 自动释放 | 临时节点断连自动删除 |

### 3.3 ZkReadWriteLock

分布式读写锁：

| 模式 | 前缀 | 规则 |
|------|------|------|
| 读锁 | `read-` | 前面无写锁则获取成功，否则等待写锁释放 |
| 写锁 | `write-` | 必须为最小节点才能获取 |

**兼容性矩阵：**

|  | 读锁 | 写锁 |
|--|------|------|
| 读锁 | ✅ 共享 | ❌ 互斥 |
| 写锁 | ❌ 互斥 | ❌ 互斥 |

## 4. 使用示例

### 4.1 基本用法

```java
// 创建客户端
ZkClientHelper helper = new ZkClientHelper("localhost:2181");
helper.connect();

// 创建锁
ZkDistributedLock lock = new ZkDistributedLock(helper, "/locks/mylock");

try {
    lock.lock();
    // 执行业务逻辑
} finally {
    lock.unlock();
    helper.close();
}
```

### 4.2 超时获取

```java
if (lock.tryLock(5, TimeUnit.SECONDS)) {
    try {
        // 获取锁成功
    } finally {
        lock.unlock();
    }
} else {
    // 超时，未获取到锁
}
```

### 4.3 读写锁

```java
ZkReadWriteLock rwLock = new ZkReadWriteLock(helper, "/locks/rw-lock");

// 读操作
rwLock.readLock().lock();
try {
    // 读取数据
} finally {
    rwLock.readLock().unlock();
}

// 写操作
rwLock.writeLock().lock();
try {
    // 写入数据
} finally {
    rwLock.writeLock().unlock();
}
```

## 5. 与其他方案对比

### 5.1 Redis vs ZooKeeper 分布式锁

| 特性 | Redis | ZooKeeper |
|------|-------|-----------|
| 一致性 | AP（最终一致） | CP（强一致） |
| 可靠性 | 主从异步，可能丢锁 | ZAB 协议保证 |
| 性能 | 高 | 中 |
| 复杂度 | 低 | 中 |
| 自动释放 | 需设置过期时间 | 临时节点自动删除 |

### 5.2 原生实现 vs Curator

| 方面 | 原生实现 | Curator |
|------|----------|---------|
| 依赖 | 仅 zookeeper 客户端 | curator-framework + recipes |
| 灵活度 | 高，可自定义 | 一般，使用预设方案 |
| 代码量 | 较多 | 较少 |
| 稳定性 | 需自己处理边界情况 | 经过生产验证 |
| 适用场景 | 学习理解原理、特殊需求 | 生产环境快速开发 |

## 6. 注意事项

### 6.1 会话超时

```java
// 建议设置合理的会话超时时间
ZkClientHelper helper = new ZkClientHelper("localhost:2181", 30000);
```

如果会话超时：
1. 临时节点会被删除
2. 锁会自动释放
3. 需要重新获取锁

### 6.2 网络分区

在网络分区场景下：
- 客户端可能与 ZooKeeper 断开
- 临时节点被删除，锁被释放
- 另一个客户端获取锁
- **可能导致两个客户端同时认为自己持有锁**

应对措施：
1. 业务层实现幂等性
2. 使用 fencing token
3. 检查锁节点是否仍存在

### 6.3 性能考虑

1. **不要创建过多锁节点**：每次获取锁都会创建节点
2. **及时释放锁**：长时间持有锁会影响其他客户端
3. **合理设置超时**：避免无限等待

## 7. 文件结构

```
cn/yj/sd/distributelock/zk/
├── DistributedLock.java          # 分布式锁接口
├── ZkClientHelper.java           # ZooKeeper 客户端辅助类
├── ZkDistributedLock.java        # 分布式锁实现
├── ZkReadWriteLock.java          # 读写锁实现
└── ZkDistributedLockExample.java # 使用示例
```

## 8. 运行要求

1. **ZooKeeper 服务**：确保 ZooKeeper 服务已启动
2. **Maven 依赖**：
   ```xml
   <dependency>
       <groupId>org.apache.zookeeper</groupId>
       <artifactId>zookeeper</artifactId>
       <version>3.8.3</version>
   </dependency>
   ```

## 9. 面试要点

1. **为什么使用临时顺序节点？**
   - 临时：防止死锁，客户端断开自动删除
   - 顺序：保证公平性，按顺序获取锁

2. **如何避免惊群效应？**
   - 每个客户端只监听前一个节点
   - 而不是所有客户端监听同一个节点

3. **ZooKeeper 锁与 Redis 锁的区别？**
   - ZK 是 CP 系统，强一致性保证
   - Redis 是 AP 系统，可能丢锁

4. **如何实现可重入？**
   - 记录持有锁的线程
   - 使用计数器记录重入次数
