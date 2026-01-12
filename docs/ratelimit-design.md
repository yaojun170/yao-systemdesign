# 限流算法核心设计文档

## 1. 概述

本文档描述漏桶（Leaky Bucket）和令牌桶（Token Bucket）两种限流算法的核心设计与实现原理。两种算法均采用 **懒更新 + CAS 无锁** 的高性能设计模式。

---

## 2. 物理模型对比

### 2.1 漏桶模型

```
       请求入水
          ↓
     ┌─────────┐
     │ ███████ │  ← 当前水量 (water)
     │ ███████ │
     │         │  ← 剩余容量 (capacity - water)
     └────┬────┘
          ↓
     固定速率漏出 (leakRate/秒)
```

**核心思想**：请求先入桶，然后以固定速率漏出处理。桶满则拒绝新请求。

### 2.2 令牌桶模型

```
     令牌以固定速率补充 (refillRate/秒)
              ↓
       ┌─────────────┐
       │  ●  ●  ●  ● │  ← 当前令牌数 (tokens)
       │  ●  ●  ●  ● │
       │             │  ← 剩余空间 (capacity - tokens)
       └──────┬──────┘
              ↓
       成功获取令牌 → 放行请求
```

**核心思想**：系统以固定速率生成令牌，请求需要获取令牌才能被处理。有令牌则放行，无令牌则拒绝。

---

## 3. 核心差异

| 特性 | 漏桶 | 令牌桶 |
|------|------|--------|
| **流量整形** | 固定速率输出，严格平滑 | 允许突发流量 |
| **突发处理** | ❌ 无法应对突发 | ✅ 可累积令牌应对突发 |
| **核心操作** | 水量增加（入桶） | 令牌减少（消费） |
| **懒更新方向** | 计算漏掉的水量 | 计算补充的令牌 |
| **适用场景** | 严格限速、流量平滑 | API 限流、弹性突发 |

---

## 4. 核心数据结构设计

### 4.1 状态封装（两者共用模式）

```java
/**
 * 桶的状态 - 不可变对象
 * 将值和时间封装在一起，便于原子更新
 */
private static class BucketState {
    final double value;           // 水量 或 令牌数
    final long lastUpdateTime;    // 上次更新时间（纳秒）
    
    BucketState(double value, long lastUpdateTime) {
        this.value = value;
        this.lastUpdateTime = lastUpdateTime;
    }
}
```

### 4.2 原子引用

```java
// 使用 AtomicReference 保证状态的原子性更新
private final AtomicReference<BucketState> state;
```

**设计要点**：
- 使用不可变对象封装状态，避免部分更新问题
- 将「值」和「时间戳」绑定，确保一致性
- 通过 CAS 原子替换整个状态对象

---

## 5. 核心算法实现

### 5.1 漏桶 - 懒更新算法

```java
/**
 * 计算当前实际水量（懒更新）
 * 
 * 核心公式：currentWater = oldWater - (elapsedTime × leakRate)
 */
private double refreshWater(BucketState currentState, long now) {
    // 1. 计算经过的时间（秒）
    double elapsedSeconds = (now - currentState.lastUpdateTime) / 1_000_000_000.0;
    
    // 2. 计算漏掉的水量
    double leakedWater = elapsedSeconds * leakRatePerSecond;
    
    // 3. 返回当前水量（不能为负数）
    return Math.max(0, currentState.water - leakedWater);
}
```

**漏桶请求处理流程**：

```java
public boolean tryAcquire(int permits) {
    while (true) {
        // 1. 读取当前状态
        BucketState currentState = state.get();
        long now = System.nanoTime();
        
        // 2. 懒更新：计算当前实际水量
        double currentWater = refreshWater(currentState, now);
        
        // 3. 判断加入新请求后是否会溢出
        double newWater = currentWater + permits;
        if (newWater > capacity) {
            return false;  // 桶满，拒绝请求
        }
        
        // 4. CAS 更新状态
        BucketState newState = new BucketState(newWater, now);
        if (state.compareAndSet(currentState, newState)) {
            return true;   // 成功入桶
        }
        
        // 5. CAS 失败，自旋重试
        Thread.onSpinWait();
    }
}
```

### 5.2 令牌桶 - 懒更新算法

```java
/**
 * 计算补充后的令牌数（懒更新）
 * 
 * 核心公式：currentTokens = min(capacity, oldTokens + elapsedTime × refillRate)
 */
private double refillTokens(BucketState currentState, long now) {
    // 1. 计算经过的时间（秒）
    double elapsedSeconds = (now - currentState.lastRefillTime) / 1_000_000_000.0;
    
    // 2. 快速返回：时间未变化，无需补充令牌
    if (elapsedSeconds <= 0) {
        return currentState.tokens;
    }
    
    // 3. 计算应该补充的令牌数
    double addTokens = elapsedSeconds * refillRatePerSecond;
    
    // 4. 返回当前令牌数（不能超过容量）
    return Math.min(capacity, currentState.tokens + addTokens);
}
```

**令牌桶请求处理流程**：

```java
public boolean tryAcquire(int permits) {
    while (true) {
        // 1. 读取当前状态
        BucketState currentState = state.get();
        long now = System.nanoTime();
        
        // 2. 懒更新：计算当前实际令牌数（补充后）
        double currentTokens = refillTokens(currentState, now);
        
        // 3. 判断令牌是否足够
        if (currentTokens < permits) {
            return false;  // 令牌不足，拒绝请求
        }
        
        // 4. 计算消费后的令牌数
        double newTokens = currentTokens - permits;
        
        // 5. CAS 更新状态
        BucketState newState = new BucketState(newTokens, now);
        if (state.compareAndSet(currentState, newState)) {
            return true;   // 成功获取令牌
        }
        
        // 6. CAS 失败，自旋重试
        Thread.onSpinWait();
    }
}
```

---

## 6. 线程安全设计

### 6.1 CAS 无锁模式

```
线程A ──┐                    ┌── 线程B
        │  state.get()       │
        ↓                    ↓
    ┌─────────────────────────────┐
    │   AtomicReference<State>    │
    └─────────────────────────────┘
        │                    │
        │ compareAndSet      │ compareAndSet
        ↓                    ↓
     成功 → 返回true      失败 → 重试
```

### 6.2 关键设计点

| 设计点 | 说明 |
|--------|------|
| **不可变状态对象** | BucketState 所有字段都是 final，避免并发修改 |
| **原子状态替换** | 使用 CAS 替换整个状态对象，保证一致性 |
| **自旋重试** | CAS 失败时使用 `Thread.onSpinWait()` 优化自旋 |
| **懒更新** | 避免定时器后台线程，降低系统开销 |

### 6.3 Thread.onSpinWait() 优化

```java
// CAS 失败时，使用 onSpinWait 提示 CPU 优化自旋等待
Thread.onSpinWait();
```

**作用**：
- 降低 CPU 功耗（x86 PAUSE 指令）
- 避免流水线停顿
- 对超线程友好

---

## 7. 懒更新 vs 定时更新

### 7.1 懒更新优势

```
传统定时更新：
┌──────────────────────────────────────────────┐
│  后台定时器线程 ──→ 每隔N毫秒更新一次状态    │
│  问题：额外线程开销、精度问题、状态同步复杂  │
└──────────────────────────────────────────────┘

懒更新方案：
┌──────────────────────────────────────────────┐
│  只在请求到来时才计算实际值                  │
│  优势：无额外线程、精度高、实现简单          │
└──────────────────────────────────────────────┘
```

### 7.2 计算示例

**漏桶懒更新**：
```
t=0:   water=8,  lastUpdate=0
       (无请求)
t=1s:  请求到达
       elapsedTime = 1s
       leaked = 1 × leakRate(2/s) = 2
       currentWater = 8 - 2 = 6
       → 判断 6 + 1 ≤ 10 → 成功
```

**令牌桶懒更新**：
```
t=0:   tokens=3, lastUpdate=0
       (无请求)
t=1s:  请求到达
       elapsedTime = 1s
       added = 1 × refillRate(5/s) = 5
       currentTokens = min(10, 3+5) = 8
       → 判断 8 ≥ 1 → 成功，剩余7
```

---

## 8. 时间精度设计

### 8.1 使用纳秒时间

```java
long now = System.nanoTime();
double elapsedSeconds = (now - lastUpdateTime) / 1_000_000_000.0;
```

**选择 `System.nanoTime()` 而非 `System.currentTimeMillis()` 的原因**：

| 特性 | nanoTime() | currentTimeMillis() |
|------|------------|---------------------|
| 精度 | 纳秒级 | 毫秒级 |
| 单调性 | ✅ 单调递增 | ❌ 可能回退（NTP调整） |
| 用途 | 时间差计算 | 绝对时间 |

---

## 9. 扩展功能（令牌桶）

### 9.1 阻塞式获取

```java
public void acquire(int permits) throws InterruptedException {
    while (!tryAcquire(permits)) {
        // 计算需要等待的时间
        long waitNanos = calculateWaitTime(permits);
        if (waitNanos > 0) {
            Thread.sleep(waitNanos / 1_000_000, (int)(waitNanos % 1_000_000));
        }
        // 检查中断
        if (Thread.interrupted()) {
            throw new InterruptedException();
        }
    }
}
```

### 9.2 带超时获取

```java
public boolean tryAcquire(int permits, long timeoutMillis) throws InterruptedException {
    long deadline = System.nanoTime() + timeoutMillis * 1_000_000L;
    
    while (!tryAcquire(permits)) {
        long remaining = deadline - System.nanoTime();
        if (remaining <= 0) {
            return false;  // 超时
        }
        // 等待，但不超过剩余时间
        long waitNanos = Math.min(calculateWaitTime(permits), remaining);
        Thread.sleep(waitNanos / 1_000_000, (int)(waitNanos % 1_000_000));
    }
    return true;
}
```

---

## 10. 使用场景指南

### 10.1 选择漏桶的场景

- **严格限速**：需要绝对平滑的输出流量
- **后端保护**：保护下游系统不被突发流量冲击
- **流量整形**：网络流量控制

### 10.2 选择令牌桶的场景

- **API 限流**：允许一定程度的突发请求
- **弹性系统**：需要在低峰期积累处理能力
- **用户体验**：避免正常用户因限流被拒绝

---

## 11. 配置参数指南

| 参数 | 漏桶 | 令牌桶 | 含义 |
|------|------|--------|------|
| capacity | 缓冲请求数量 | 最大突发流量 | 容量设置 |
| rate | 平滑处理速率 | 稳态吞吐量 | 速率设置 |

**配置示例**：

```java
// 漏桶：容量100，每秒处理10个请求
LeakyBucketRateLimiter leaky = new LeakyBucketRateLimiter(100, 10);

// 令牌桶：容量100，每秒补充10个令牌
TokenBucketRateLimiter token = new TokenBucketRateLimiter(100, 10);
```

---

## 12. 性能特点

| 特性 | 说明 |
|------|------|
| **无锁设计** | CAS 操作，高并发下性能优异 |
| **无后台线程** | 懒更新机制，零额外线程开销 |
| **内存友好** | 仅维护一个原子引用和两个 double 值 |
| **GC 友好** | 状态对象较小，快速分配和回收 |

---

## 13. 文件结构

```
ratelimit/
├── leakbucket/
│   ├── LeakyBucketRateLimiter.java        # 漏桶限流器实现
│   └── LeakyBucketRateLimiterExample.java # 使用示例
├── tokenbucket/
│   ├── TokenBucketRateLimiter.java        # 令牌桶限流器实现
│   └── TokenBucketExample.java            # 使用示例
└── ratelimit-design.md                    # 本设计文档
```

---

*文档版本: 1.0 | 作者: YaoJun | 日期: 2026-01-09*
