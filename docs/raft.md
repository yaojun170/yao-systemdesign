# Raft 协议 Java 实现

## 概述

这是一个完整的 Raft 分布式一致性协议的 Java 实现，用于学习和理解 Raft 的核心原理。

## 项目结构

```
cn.yj.sd.raft
├── core/                           # 核心组件
│   ├── NodeId.java                # 节点唯一标识
│   ├── RaftState.java             # 节点状态枚举（Follower/Candidate/Leader）
│   └── RaftNode.java              # 🔥 Raft 节点核心实现
├── log/                            # 日志模块
│   ├── LogEntry.java              # 日志条目（包含命令定义）
│   ├── RaftLog.java               # 日志管理器
│   └── StateMachine.java          # 状态机接口及 KV 实现
├── rpc/                            # RPC 通信
│   ├── RaftRpcService.java        # RPC 服务接口
│   ├── RaftRpcClient.java         # RPC 客户端
│   ├── RaftRpcServer.java         # RPC 服务端
│   └── message/                   # RPC 消息
│       ├── RequestVote.java       # 投票请求
│       ├── RequestVoteResponse.java
│       ├── AppendEntries.java     # 追加日志请求
│       └── AppendEntriesResponse.java
└── demo/
    └── RaftClusterDemo.java       # 集群演示程序
```

## 核心功能实现

### 1. Leader 选举

```
选举流程：
1. Follower 选举超时 → 转为 Candidate
2. Candidate 增加任期、投票给自己、发送 RequestVote
3. 收到多数派投票 → 成为 Leader
4. 发现更高任期 → 转为 Follower

关键参数：
- 心跳间隔: 150ms
- 选举超时: 300-500ms（随机化防止活锁）
```

### 2. 日志复制

```
复制流程：
1. 客户端发送命令给 Leader
2. Leader 追加到本地日志
3. Leader 发送 AppendEntries 给所有 Follower
4. 收到多数派 ACK → 提交日志
5. 下次心跳通知 Follower 提交
6. 各节点应用日志到状态机

一致性检查：
- prevLogIndex + prevLogTerm 匹配检查
- 不匹配则回退重试
```

### 3. 安全性保证

- **Election Safety**: 每个任期最多一个 Leader
- **Leader Append-Only**: Leader 只追加不删除日志
- **Log Matching**: 相同 index+term 确保之前所有日志相同
- **Leader Completeness**: 已提交日志必在后续 Leader 中

## 运行演示

```bash
# 编译
mvn compile

# 运行演示程序
mvn exec:java -Dexec.mainClass="cn.yj.sd.raft.demo.RaftClusterDemo"
```

### 演示内容

1. 启动 3 节点集群
2. 观察 Leader 选举过程
3. 通过 Leader 提交 KV 命令
4. 观察日志复制到所有节点
5. 模拟 Leader 故障
6. 观察新 Leader 选举
7. 验证数据一致性

## 代码示例

### 创建并启动集群

```java
// 定义集群节点
List<NodeId> cluster = Arrays.asList(
    new NodeId("node-0", "localhost", 9000),
    new NodeId("node-1", "localhost", 9001),
    new NodeId("node-2", "localhost", 9002)
);

// 创建节点
RaftNode node0 = new RaftNode(cluster.get(0), cluster);
RaftNode node1 = new RaftNode(cluster.get(1), cluster);
RaftNode node2 = new RaftNode(cluster.get(2), cluster);

// 启动
node0.start();
node1.start();
node2.start();
```

### 提交命令

```java
// 找到 Leader
RaftNode leader = findLeader(nodes);

// 提交命令
CompletableFuture<Object> future = leader.submitCommand(
    new LogEntry.SetCommand("key", "value")
);

// 等待结果
Object result = future.get(5, TimeUnit.SECONDS);
```

### 查询状态机

```java
StateMachine.KVStateMachine sm = (StateMachine.KVStateMachine) node.getStateMachine();
String value = sm.get("key");
Map<String, String> all = sm.getAll();
```

## 实现细节

### RaftNode 核心状态

```java
// 持久化状态（每个节点）
long currentTerm;        // 当前任期
NodeId votedFor;         // 当前任期投票给谁
RaftLog log;             // 日志

// 易失性状态（每个节点）
long commitIndex;        // 已提交的最高日志索引
long lastApplied;        // 已应用的最高日志索引
RaftState state;         // 当前状态

// 易失性状态（仅 Leader）
Map<NodeId, Long> nextIndex;   // 下一个要发送的日志索引
Map<NodeId, Long> matchIndex;  // 已复制的最高日志索引
```

### RequestVote 处理逻辑

```java
// 伪代码
if (request.term < currentTerm) {
    return reject;
}

if (request.term > currentTerm) {
    becomeFollower(request.term);
}

boolean canVote = (votedFor == null || votedFor == candidateId);
boolean logUpToDate = isLogUpToDate(request);

if (canVote && logUpToDate) {
    votedFor = candidateId;
    return grant;
}
return reject;
```

### AppendEntries 处理逻辑

```java
// 伪代码
if (request.term < currentTerm) {
    return fail;
}

if (request.term >= currentTerm) {
    becomeFollower(request.term);
}

// 一致性检查
if (!matchLog(request.prevLogIndex, request.prevLogTerm)) {
    return fail;
}

// 追加日志
appendEntries(request.entries);

// 更新 commitIndex
if (request.leaderCommit > commitIndex) {
    commitIndex = min(request.leaderCommit, lastLogIndex);
}

return success;
```

## 注意事项

1. **学习用途**: 这是简化实现，适合学习理解 Raft 原理
2. **非生产级**: 未实现日志持久化、快照、成员变更等高级功能
3. **内存存储**: 所有数据存储在内存中，重启丢失
4. **简单 RPC**: 使用 Java Socket 实现，非高性能

## 扩展阅读

- [Raft 论文原文](https://raft.github.io/raft.pdf)
- [Raft 可视化演示](https://raft.github.io/)
- [etcd Raft 实现](https://github.com/etcd-io/raft)

## 与 Paxos/ZAB 对比

| 特性 | Raft | Paxos | ZAB |
|------|------|-------|-----|
| 可理解性 | ⭐⭐⭐⭐⭐ | ⭐⭐ | ⭐⭐⭐ |
| Leader | 强制 | 可选 | 强制 |
| 日志连续 | 是 | 否 | 是 |
| 代表系统 | etcd | Spanner | ZooKeeper |
