package cn.yj.sd.raft.rpc;

import cn.yj.sd.raft.rpc.message.*;

/**
 * Raft RPC 服务接口
 * 
 * 定义 Raft 节点需要响应的 RPC 调用
 */
public interface RaftRpcService {
    
    /**
     * 处理 RequestVote RPC
     * 
     * @param request 投票请求
     * @return 投票响应
     */
    RequestVoteResponse handleRequestVote(RequestVote request);
    
    /**
     * 处理 AppendEntries RPC
     * 
     * @param request 追加条目请求
     * @return 追加条目响应
     */
    AppendEntriesResponse handleAppendEntries(AppendEntries request);
}
