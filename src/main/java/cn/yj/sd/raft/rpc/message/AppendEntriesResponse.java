package cn.yj.sd.raft.rpc.message;

import cn.yj.sd.raft.core.NodeId;
import java.io.Serializable;

/**
 * AppendEntries RPC 响应
 */
public class AppendEntriesResponse implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 响应节点 ID */
    private final NodeId followerId;

    /** 当前任期号，Leader 用于更新自己的任期 */
    private final long term;

    /** 是否成功追加 */
    private final boolean success;

    /**
     * Follower 期望的下一个日志索引
     * 用于快速回退（优化），当 success=false 时使用
     */
    private final long matchIndex;

    public AppendEntriesResponse(NodeId followerId, long term, boolean success, long matchIndex) {
        this.followerId = followerId;
        this.term = term;
        this.success = success;
        this.matchIndex = matchIndex;
    }

    /**
     * 创建成功响应
     */
    public static AppendEntriesResponse success(NodeId followerId, long term, long matchIndex) {
        return new AppendEntriesResponse(followerId, term, true, matchIndex);
    }

    /**
     * 创建失败响应
     */
    public static AppendEntriesResponse fail(NodeId followerId, long term, long expectedIndex) {
        return new AppendEntriesResponse(followerId, term, false, expectedIndex);
    }

    public NodeId getFollowerId() {
        return followerId;
    }

    public long getTerm() {
        return term;
    }

    public boolean isSuccess() {
        return success;
    }

    public long getMatchIndex() {
        return matchIndex;
    }

    @Override
    public String toString() {
        return String.format("AppendEntriesResponse{follower=%s, term=%d, success=%s, matchIndex=%d}",
                followerId.getNodeId(), term, success, matchIndex);
    }
}
