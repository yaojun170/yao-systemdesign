package cn.yj.sd.raft.rpc.message;

import cn.yj.sd.raft.core.NodeId;
import cn.yj.sd.raft.log.LogEntry;
import java.io.Serializable;
import java.util.List;

/**
 * AppendEntries RPC 请求
 * 
 * 由 Leader 发起，用于：
 * 1. 复制日志条目到 Follower
 * 2. 作为心跳维持 Leader 地位（entries 为空时）
 * 
 * 一致性检查：
 * - Follower 检查 prevLogIndex 和 prevLogTerm 是否匹配
 * - 如果不匹配，拒绝追加，Leader 会回退重试
 */
public class AppendEntries implements Serializable {

    private static final long serialVersionUID = 1L;

    /** Leader 的任期号 */
    private final long term;

    /** Leader ID，Follower 用于重定向客户端请求 */
    private final NodeId leaderId;

    /** 紧邻新日志条目之前的日志索引 */
    private final long prevLogIndex;

    /** prevLogIndex 对应的任期号 */
    private final long prevLogTerm;

    /** 要追加的日志条目（心跳时为空） */
    private final List<LogEntry> entries;

    /** Leader 已知的最高已提交日志索引 */
    private final long leaderCommit;

    public AppendEntries(long term, NodeId leaderId, long prevLogIndex, long prevLogTerm,
            List<LogEntry> entries, long leaderCommit) {
        this.term = term;
        this.leaderId = leaderId;
        this.prevLogIndex = prevLogIndex;
        this.prevLogTerm = prevLogTerm;
        this.entries = entries;
        this.leaderCommit = leaderCommit;
    }

    /**
     * 创建心跳消息（不含日志条目）
     */
    public static AppendEntries heartbeat(long term, NodeId leaderId,
            long prevLogIndex, long prevLogTerm,
            long leaderCommit) {
        return new AppendEntries(term, leaderId, prevLogIndex, prevLogTerm,
                List.of(), leaderCommit);
    }

    public long getTerm() {
        return term;
    }

    public NodeId getLeaderId() {
        return leaderId;
    }

    public long getPrevLogIndex() {
        return prevLogIndex;
    }

    public long getPrevLogTerm() {
        return prevLogTerm;
    }

    public List<LogEntry> getEntries() {
        return entries;
    }

    public long getLeaderCommit() {
        return leaderCommit;
    }

    public boolean isHeartbeat() {
        return entries == null || entries.isEmpty();
    }

    @Override
    public String toString() {
        return String.format("AppendEntries{term=%d, leader=%s, prevLogIndex=%d, prevLogTerm=%d, " +
                "entriesCount=%d, leaderCommit=%d}",
                term, leaderId.getNodeId(), prevLogIndex, prevLogTerm,
                entries != null ? entries.size() : 0, leaderCommit);
    }
}
