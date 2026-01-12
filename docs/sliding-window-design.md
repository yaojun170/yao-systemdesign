# 滑动窗口限流算法设计文档

## 1. 算法概述

### 1.1 什么是滑动窗口限流

滑动窗口限流是一种将时间窗口划分为多个小的时间片段（子窗口）的限流算法。通过滑动的方式，动态计算当前时间窗口内的请求总数，从而实现平滑的限流效果。

### 1.2 与固定窗口的对比

| 特性 | 固定窗口 | 滑动窗口 |
|------|----------|----------|
| 实现复杂度 | 简单 | 中等 |
| 内存开销 | 低 | 较低 |
| 边界突发问题 | 存在 | 解决 |
| 限流精度 | 粗粒度 | 细粒度（可调） |
| 适用场景 | 简单限流 | 精确限流 |

### 1.3 边界突发问题示例

```
固定窗口问题：
时间线:  |-------- 第1秒 --------|-------- 第2秒 --------|
请求:           [在第1秒末尾发送100个]  [在第2秒开头发送100个]
结果:    两批请求都被允许，实际在1秒内通过了200个请求！

滑动窗口解决：
时间线:  |-------- 滑动窗口 --------|
                 ↓ 当前时间
请求:    统计过去整个窗口期内的请求数
结果:    只允许100个请求通过，有效限流
```

## 2. 核心设计

### 2.1 数据结构

```java
// 子窗口计数器映射
ConcurrentHashMap<Long, LongAdder> subWindowCounters;

// 配置参数
long windowSizeMs;      // 时间窗口大小（毫秒）
int subWindowCount;     // 子窗口数量
long subWindowSizeMs;   // 每个子窗口大小（毫秒）
long maxPermits;        // 最大允许请求数
```

### 2.2 核心算法流程

```
tryAcquire():
    1. 计算当前时间所属的子窗口索引
       currentIndex = currentTime / subWindowSize
    
    2. 计算窗口起始位置
       startIndex = (currentTime - windowSize) / subWindowSize
    
    3. 统计 [startIndex, currentIndex] 范围内的请求总数
       count = sum(subWindowCounters[startIndex..currentIndex])
    
    4. 判断是否超限
       if (count + permits > maxPermits) return false
    
    5. 增加当前子窗口的计数
       subWindowCounters[currentIndex].add(permits)
       return true
```

### 2.3 子窗口索引计算

```
时间线（假设 windowSize=1000ms, subWindowCount=10）:

0ms    100ms   200ms   300ms   ...   900ms   1000ms
|-------|-------|-------|-------|-----|-------|-------|
  idx=0   idx=1   idx=2   idx=3   ...   idx=9   idx=10

子窗口索引 = 当前时间(ms) / 子窗口大小(ms)
           = 当前时间(ms) / 100
           = 当前时间(ms) / (windowSize / subWindowCount)
```

## 3. 线程安全设计

### 3.1 并发数据结构选择

| 组件 | 选择 | 原因 |
|------|------|------|
| 子窗口计数器容器 | `ConcurrentHashMap` | 高并发读写，分段锁 |
| 计数器 | `LongAdder` | 比 AtomicLong 更高的并发性能 |
| 总计数 | `AtomicLong` | 用于快速判断，减少遍历 |

### 3.2 LongAdder vs AtomicLong

```
高并发场景下：

AtomicLong:
  Thread1 → CAS → 成功
  Thread2 → CAS → 失败 → 重试 → 成功
  Thread3 → CAS → 失败 → 重试 → 失败 → 重试 → 成功
  → CAS 竞争激烈，大量自旋

LongAdder:
  Thread1 → Cell[0]++ → 成功
  Thread2 → Cell[1]++ → 成功
  Thread3 → Cell[2]++ → 成功
  → 分散热点，减少竞争
  → 读取时: sum(Cell[0..n])
```

### 3.3 惰性清理策略

```java
// 避免频繁清理带来的性能开销
private void cleanupExpiredSubWindowsIfNeeded(long now) {
    // 1. 快速检查：距离上次清理是否超过间隔
    if (now - lastCleanupTime < cleanupIntervalMs) {
        return;
    }
    
    // 2. 双重检查锁定
    synchronized (this) {
        if (now - lastCleanupTime < cleanupIntervalMs) {
            return;
        }
        
        // 3. 执行清理：移除过期子窗口
        // ...
    }
}
```

## 4. 性能分析

### 4.1 时间复杂度

| 操作 | 时间复杂度 | 说明 |
|------|------------|------|
| tryAcquire | O(n) | n = 子窗口数量 |
| getCurrentCount | O(n) | n = 子窗口数量 |
| 清理过期窗口 | O(m) | m = 过期窗口数量 |

### 4.2 空间复杂度

```
空间占用 = O(n + k)
  n = 子窗口数量（活跃窗口）
  k = LongAdder 内部 Cell 数量（与并发度相关）

实际内存估算：
  - 每个子窗口: ~64 bytes (Long key + LongAdder)
  - 10个子窗口的限流器: ~1KB
  - 60个子窗口的限流器: ~4KB
```

### 4.3 性能优化建议

1. **子窗口数量选择**
   - 推荐: 10-60 个子窗口
   - 子窗口大小建议: 50ms - 500ms
   - 过多子窗口增加遍历开销

2. **清理策略调优**
   - 默认清理间隔 = 窗口大小
   - 高 QPS 场景可适当增加清理间隔

3. **快速失败优化**
   ```java
   // 使用 totalCount 做快速判断
   if (totalCount.get() >= maxPermits * 1.2) {
       // 快速拒绝，避免遍历
       return false;
   }
   ```

## 5. 使用指南

### 5.1 基础用法

```java
// 方式1：使用工厂方法
SlidingWindowRateLimiter limiter = SlidingWindowRateLimiter.createPerSecond(100);

// 方式2：自定义配置
SlidingWindowRateLimiter limiter = new SlidingWindowRateLimiter(
    1000,   // 窗口大小：1秒
    10,     // 子窗口数量：10个
    100     // 最大请求数：100
);

// 请求时调用
if (limiter.tryAcquire()) {
    // 请求被允许，执行业务逻辑
    doBusinessLogic();
} else {
    // 请求被限流
    return RateLimitResponse.rejected();
}
```

### 5.2 多资源限流

```java
public class MultiResourceRateLimiter {
    private final Map<String, SlidingWindowRateLimiter> limiters = new ConcurrentHashMap<>();
    
    public boolean tryAcquire(String resource) {
        SlidingWindowRateLimiter limiter = limiters.computeIfAbsent(
            resource,
            k -> SlidingWindowRateLimiter.createPerSecond(100)
        );
        return limiter.tryAcquire();
    }
}
```

### 5.3 与 Spring 集成

```java
@Component
public class RateLimitInterceptor implements HandlerInterceptor {
    
    private final SlidingWindowRateLimiter globalLimiter = 
        SlidingWindowRateLimiter.createPerSecond(1000);
    
    @Override
    public boolean preHandle(HttpServletRequest request, 
                            HttpServletResponse response, 
                            Object handler) {
        if (!globalLimiter.tryAcquire()) {
            response.setStatus(429); // Too Many Requests
            return false;
        }
        return true;
    }
}
```

## 6. 适用场景

### 6.1 推荐场景

- ✅ API 接口限流
- ✅ 用户请求频率控制
- ✅ 资源访问保护
- ✅ 需要精确限流的场景
- ✅ 单机限流

### 6.2 不推荐场景

- ❌ 需要分布式限流（推荐 Redis + Lua）
- ❌ 需要削峰填谷（推荐 Leaky Bucket）
- ❌ 需要应对突发流量（推荐 Token Bucket）
- ❌ 极高 QPS（>100万/秒）场景

## 7. 与其他限流算法对比

| 算法 | 优点 | 缺点 | 适用场景 |
|------|------|------|----------|
| **固定窗口** | 实现简单 | 边界突发问题 | 简单限流 |
| **滑动窗口** | 平滑限流，解决边界问题 | 实现较复杂 | 精确限流 |
| **漏桶** | 流量整形，输出平滑 | 无法应对突发 | 削峰填谷 |
| **令牌桶** | 允许一定突发 | 实现复杂 | 突发流量场景 |

## 8. 监控指标

生产环境建议监控以下指标：

```java
// 1. 当前窗口请求数
limiter.getCurrentCount()

// 2. 剩余可用许可
limiter.getAvailablePermits()

// 3. 限流拒绝率
rejectedCount / totalRequests

// 4. 请求响应时间分布
// P50, P90, P99 等
```

## 9. 总结

滑动窗口限流算法是一种介于固定窗口和更复杂算法（如令牌桶）之间的平衡选择：

1. **解决了固定窗口的边界突发问题**
2. **实现复杂度适中**
3. **内存开销可控**
4. **精度可调（通过子窗口数量）**
5. **适合大多数 API 限流场景**

在生产环境中，推荐根据实际业务需求选择合适的子窗口配置，并结合监控系统实时观察限流效果。
