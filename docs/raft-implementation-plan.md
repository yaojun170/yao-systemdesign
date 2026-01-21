# Raft 协议实现计划

## 一、实现目标

在本项目中实现一个完整的、可运行的 Raft 协议，包含以下核心功能：

1. **Leader 选举**（Leader Election）
2. **日志复制**（Log Replication）
3. **安全性保证**（Safety）
4. **成员管理**（基础版）

## 二、模块设计

```
cn.yj.sd.raft
├── core/                    # 核心组件
│   ├── RaftNode.java       # Raft 节点主类
│   ├── RaftState.java      # 节点状态枚举
│   └── NodeId.java         # 节点标识
├── log/                     # 日志模块
│   ├── LogEntry.java       # 日志条目
│   ├── RaftLog.java        # 日志管理器
│   └── StateMachine.java   # 状态机接口
├── rpc/                     # RPC 通信
│   ├── RaftRpcService.java # RPC 服务接口
│   ├── RaftRpcClient.java  # RPC 客户端
│   ├── RaftRpcServer.java  # RPC 服务端
│   └── message/            # RPC 消息
│       ├── RequestVote.java
│       ├── RequestVoteResponse.java
│       ├── AppendEntries.java
│       └── AppendEntriesResponse.java
├── election/                # 选举模块
│   └── ElectionManager.java
├── replication/             # 复制模块
│   └── ReplicationManager.java
└── demo/                    # 演示
    └── RaftClusterDemo.java
```

## 三、实现步骤

### 阶段 1：基础数据结构
- [x] NodeId - 节点标识
- [x] RaftState - 节点状态枚举
- [x] LogEntry - 日志条目
- [x] RaftLog - 日志管理

### 阶段 2：RPC 消息定义
- [x] RequestVote / RequestVoteResponse
- [x] AppendEntries / AppendEntriesResponse

### 阶段 3：核心节点实现
- [x] RaftNode - 节点主逻辑
- [x] ElectionManager - 选举管理
- [x] ReplicationManager - 复制管理

### 阶段 4：网络通信
- [x] 基于 Socket 的简单 RPC 实现
- [x] 消息序列化/反序列化

### 阶段 5：集成测试
- [ ] 3 节点集群演示
- [ ] Leader 选举测试
- [ ] 日志复制测试
- [ ] 故障恢复测试

## 四、关键设计决策

1. **通信方式**：使用 Java Socket 实现简单 RPC，避免引入额外依赖
2. **序列化**：使用 Java 原生序列化，简化实现
3. **存储**：内存存储，不做持久化（简化学习版本）
4. **状态机**：提供简单的 KV 存储状态机示例

## 五、参考资料

- [Raft 论文](https://raft.github.io/raft.pdf)
- [Raft 可视化](https://raft.github.io/)
