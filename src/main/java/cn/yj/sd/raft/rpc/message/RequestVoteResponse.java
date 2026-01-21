package cn.yj.sd.raft.rpc.message;

import cn.yj.sd.raft.core.NodeId;
import java.io.Serializable;

/**
 * RequestVote RPC 响应
 */
public class RequestVoteResponse implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 响应节点 ID */
    private final NodeId voterId;

    /** 当前任期号，候选人用于更新自己的任期 */
    private final long term;

    /** 是否同意投票 */
    private final boolean voteGranted;

    public RequestVoteResponse(NodeId voterId, long term, boolean voteGranted) {
        this.voterId = voterId;
        this.term = term;
        this.voteGranted = voteGranted;
    }

    public NodeId getVoterId() {
        return voterId;
    }

    public long getTerm() {
        return term;
    }

    public boolean isVoteGranted() {
        return voteGranted;
    }

    @Override
    public String toString() {
        return String.format("RequestVoteResponse{voter=%s, term=%d, granted=%s}",
                voterId.getNodeId(), term, voteGranted);
    }
}
