package cn.yj.sd.raft.core;

/**
 * Raft 节点状态枚举
 * 
 * Raft 协议中节点只能处于以下三种状态之一：
 * - FOLLOWER: 跟随者，被动接受来自 Leader 的日志和心跳
 * - CANDIDATE: 候选人，正在发起选举
 * - LEADER: 领导者，负责处理客户端请求和日志复制
 */
public enum RaftState {

    /**
     * 跟随者状态
     * - 响应来自 Leader 和 Candidate 的 RPC
     * - 如果选举超时没有收到心跳，转换为 Candidate
     */
    FOLLOWER("Follower"),

    /**
     * 候选人状态
     * - 增加当前任期号，投票给自己
     * - 发送 RequestVote RPC 给所有其他节点
     * - 如果收到多数派投票，成为 Leader
     * - 如果收到来自新 Leader 的 AppendEntries，转换为 Follower
     * - 如果选举超时，开始新一轮选举
     */
    CANDIDATE("Candidate"),

    /**
     * 领导者状态
     * - 发送心跳（空的 AppendEntries）给所有节点
     * - 接收客户端请求，追加日志条目
     * - 复制日志给 Follower
     * - 如果发现更高任期，转换为 Follower
     */
    LEADER("Leader");

    private final String name;

    RaftState(String name) {
        this.name = name;
    }

    public String getName() {
        return name;
    }

    @Override
    public String toString() {
        return name;
    }
}
