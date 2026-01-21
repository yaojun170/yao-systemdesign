package cn.yj.sd.raft.rpc.message;

import cn.yj.sd.raft.core.NodeId;
import java.io.Serializable;

/**
 * RequestVote RPC 请求
 * 
 * 由 Candidate 发起，用于请求其他节点投票
 * 
 * 投票规则（接收方）：
 * 1. 如果 term < currentTerm，拒绝投票
 * 2. 如果 votedFor 为空或等于 candidateId，且候选人日志至少和自己一样新，则投票
 */
public class RequestVote implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 候选人的任期号 */
    private final long term;

    /** 请求投票的候选人 ID */
    private final NodeId candidateId;

    /** 候选人最后日志条目的索引 */
    private final long lastLogIndex;

    /** 候选人最后日志条目的任期号 */
    private final long lastLogTerm;

    public RequestVote(long term, NodeId candidateId, long lastLogIndex, long lastLogTerm) {
        this.term = term;
        this.candidateId = candidateId;
        this.lastLogIndex = lastLogIndex;
        this.lastLogTerm = lastLogTerm;
    }

    public long getTerm() {
        return term;
    }

    public NodeId getCandidateId() {
        return candidateId;
    }

    public long getLastLogIndex() {
        return lastLogIndex;
    }

    public long getLastLogTerm() {
        return lastLogTerm;
    }

    @Override
    public String toString() {
        return String.format("RequestVote{term=%d, candidate=%s, lastLogIndex=%d, lastLogTerm=%d}",
                term, candidateId.getNodeId(), lastLogIndex, lastLogTerm);
    }
}
