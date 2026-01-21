package cn.yj.sd.raft.core;

import cn.yj.sd.raft.log.*;
import cn.yj.sd.raft.rpc.*;
import cn.yj.sd.raft.rpc.message.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Raft 节点核心实现
 * 
 * 实现了 Raft 协议的三大核心功能：
 * 1. Leader 选举
 * 2. 日志复制
 * 3. 安全性保证
 * 
 * 核心设计：
 * - 使用单线程事件循环处理状态转换，避免复杂的并发问题
 * - RPC 处理在独立线程，通过锁保证线程安全
 * - 定时任务触发选举超时和心跳
 */
public class RaftNode implements RaftRpcService {

    private static final Logger logger = LoggerFactory.getLogger(RaftNode.class);

    // ==================== 配置常量 ====================

    /** 心跳间隔（毫秒） */
    private static final int HEARTBEAT_INTERVAL = 150;

    /** 选举超时范围下限（毫秒） */
    private static final int ELECTION_TIMEOUT_MIN = 300;

    /** 选举超时范围上限（毫秒） */
    private static final int ELECTION_TIMEOUT_MAX = 500;

    // ==================== 节点信息 ====================

    /** 当前节点 ID */
    private final NodeId nodeId;

    /** 集群中所有节点（包括自己） */
    private final List<NodeId> cluster;

    /** 多数派数量 */
    private final int majority;

    // ==================== 持久化状态（所有节点） ====================

    /** 当前任期号（单调递增） */
    private volatile long currentTerm;

    /** 当前任期投票给的候选人 ID，null 表示未投票 */
    private volatile NodeId votedFor;

    /** 日志 */
    private final RaftLog log;

    // ==================== 易失性状态（所有节点） ====================

    /** 已知被提交的最高日志索引 */
    private volatile long commitIndex;

    /** 已应用到状态机的最高日志索引 */
    private volatile long lastApplied;

    /** 当前节点状态 */
    private volatile RaftState state;

    /** 当前 Leader ID */
    private volatile NodeId leaderId;

    // ==================== 易失性状态（仅 Leader） ====================

    /** 对于每个节点，要发送给它的下一个日志条目索引 */
    private final Map<NodeId, Long> nextIndex;

    /** 对于每个节点，已知已复制到该节点的最高日志索引 */
    private final Map<NodeId, Long> matchIndex;

    // ==================== 组件 ====================

    /** 状态机 */
    private final StateMachine stateMachine;

    /** RPC 客户端 */
    private final RaftRpcClient rpcClient;

    /** RPC 服务端 */
    private final RaftRpcServer rpcServer;

    /** 调度器（心跳和选举超时） */
    private final ScheduledExecutorService scheduler;

    /** 选举超时任务 */
    private ScheduledFuture<?> electionTimeoutTask;

    /** 心跳任务 */
    private ScheduledFuture<?> heartbeatTask;

    /** 主锁，保护状态转换 */
    private final ReentrantLock lock;

    /** 运行状态 */
    private final AtomicBoolean running;

    /** 随机数生成器 */
    private final Random random;

    /** 客户端命令回调 */
    private final Map<Long, CompletableFuture<Object>> pendingCommands;

    // ==================== 构造函数 ====================

    public RaftNode(NodeId nodeId, List<NodeId> cluster) {
        this(nodeId, cluster, new StateMachine.KVStateMachine());
    }

    public RaftNode(NodeId nodeId, List<NodeId> cluster, StateMachine stateMachine) {
        this.nodeId = nodeId;
        this.cluster = new ArrayList<>(cluster);
        this.majority = cluster.size() / 2 + 1;

        // 初始化持久化状态
        this.currentTerm = 0;
        this.votedFor = null;
        this.log = new RaftLog();

        // 初始化易失性状态
        this.commitIndex = 0;
        this.lastApplied = 0;
        this.state = RaftState.FOLLOWER;
        this.leaderId = null;

        // 初始化 Leader 状态
        this.nextIndex = new ConcurrentHashMap<>();
        this.matchIndex = new ConcurrentHashMap<>();

        // 初始化组件
        this.stateMachine = stateMachine;
        this.rpcClient = new RaftRpcClient();
        this.rpcServer = new RaftRpcServer(nodeId, this);
        this.scheduler = Executors.newScheduledThreadPool(2, r -> {
            Thread t = new Thread(r, "raft-scheduler-" + nodeId.getNodeId());
            t.setDaemon(true);
            return t;
        });

        this.lock = new ReentrantLock();
        this.running = new AtomicBoolean(false);
        this.random = new Random();
        this.pendingCommands = new ConcurrentHashMap<>();
    }

    // ==================== 生命周期管理 ====================

    /**
     * 启动 Raft 节点
     */
    public void start() throws IOException {
        if (running.compareAndSet(false, true)) {
            logger.info("{} starting...", nodeId);

            // 启动 RPC 服务
            rpcServer.start();

            // 开始选举超时计时
            resetElectionTimeout();

            // 启动日志应用线程
            startApplyLoop();

            logger.info("{} started as {}", nodeId, state);
        }
    }

    /**
     * 停止 Raft 节点
     */
    public void stop() {
        if (running.compareAndSet(true, false)) {
            logger.info("{} stopping...", nodeId);

            cancelElectionTimeout();
            cancelHeartbeat();

            rpcServer.stop();
            rpcClient.shutdown();
            scheduler.shutdown();

            logger.info("{} stopped", nodeId);
        }
    }

    // ==================== Leader 选举 ====================

    /**
     * 重置选举超时
     */
    private void resetElectionTimeout() {
        cancelElectionTimeout();

        int timeout = ELECTION_TIMEOUT_MIN + random.nextInt(ELECTION_TIMEOUT_MAX - ELECTION_TIMEOUT_MIN);
        electionTimeoutTask = scheduler.schedule(this::onElectionTimeout, timeout, TimeUnit.MILLISECONDS);
    }

    /**
     * 取消选举超时
     */
    private void cancelElectionTimeout() {
        if (electionTimeoutTask != null && !electionTimeoutTask.isDone()) {
            electionTimeoutTask.cancel(false);
        }
    }

    /**
     * 选举超时处理
     */
    private void onElectionTimeout() {
        lock.lock();
        try {
            if (!running.get() || state == RaftState.LEADER) {
                return;
            }

            logger.info("{} election timeout, starting election for term {}", nodeId, currentTerm + 1);
            startElection();
        } finally {
            lock.unlock();
        }
    }

    /**
     * 开始选举
     */
    private void startElection() {
        // 1. 转换为候选人状态
        state = RaftState.CANDIDATE;

        // 2. 增加当前任期
        currentTerm++;

        // 3. 投票给自己
        votedFor = nodeId;

        // 4. 重置选举超时
        resetElectionTimeout();

        // 5. 向所有其他节点发送 RequestVote RPC
        long lastLogIndex = log.getLastIndex();
        long lastLogTerm = log.getLastTerm();
        RequestVote request = new RequestVote(currentTerm, nodeId, lastLogIndex, lastLogTerm);

        // 统计投票（自己已经投了一票）
        int[] voteCount = { 1 };
        long electionTerm = currentTerm;

        for (NodeId peer : cluster) {
            if (peer.equals(nodeId)) {
                continue;
            }

            rpcClient.sendRequestVoteAsync(peer, request)
                    .thenAccept(response -> handleVoteResponse(response, electionTerm, voteCount));
        }
    }

    /**
     * 处理投票响应
     */
    private void handleVoteResponse(RequestVoteResponse response, long electionTerm, int[] voteCount) {
        if (response == null) {
            return;
        }

        lock.lock();
        try {
            // 检查响应是否仍然有效
            if (state != RaftState.CANDIDATE || currentTerm != electionTerm) {
                return;
            }

            // 如果发现更高任期，转换为 Follower
            if (response.getTerm() > currentTerm) {
                becomeFollower(response.getTerm(), null);
                return;
            }

            // 统计投票
            if (response.isVoteGranted()) {
                voteCount[0]++;
                logger.debug("{} received vote from {}, total votes: {}/{}",
                        nodeId, response.getVoterId(), voteCount[0], majority);

                // 获得多数派投票，成为 Leader
                if (voteCount[0] >= majority) {
                    becomeLeader();
                }
            }
        } finally {
            lock.unlock();
        }
    }

    /**
     * 成为 Leader
     */
    private void becomeLeader() {
        logger.info("{} became LEADER for term {}", nodeId, currentTerm);

        state = RaftState.LEADER;
        leaderId = nodeId;

        // 取消选举超时
        cancelElectionTimeout();

        // 初始化 Leader 状态
        long lastLogIndex = log.getLastIndex();
        for (NodeId peer : cluster) {
            nextIndex.put(peer, lastLogIndex + 1);
            matchIndex.put(peer, 0L);
        }

        // 追加一条 NoOp 日志（确保提交之前任期的日志）
        log.append(currentTerm, new LogEntry.NoOpCommand());

        // 开始心跳
        startHeartbeat();

        // 立即发送心跳
        sendHeartbeats();
    }

    /**
     * 成为 Follower
     */
    private void becomeFollower(long term, NodeId newLeaderId) {
        logger.info("{} became FOLLOWER for term {}, leader={}", nodeId, term, newLeaderId);

        state = RaftState.FOLLOWER;
        currentTerm = term;
        votedFor = null;
        leaderId = newLeaderId;

        // 取消心跳（如果之前是 Leader）
        cancelHeartbeat();

        // 重置选举超时
        resetElectionTimeout();
    }

    // ==================== 日志复制 ====================

    /**
     * 开始心跳
     */
    private void startHeartbeat() {
        cancelHeartbeat();
        heartbeatTask = scheduler.scheduleAtFixedRate(
                this::sendHeartbeats,
                0,
                HEARTBEAT_INTERVAL,
                TimeUnit.MILLISECONDS);
    }

    /**
     * 取消心跳
     */
    private void cancelHeartbeat() {
        if (heartbeatTask != null && !heartbeatTask.isDone()) {
            heartbeatTask.cancel(false);
        }
    }

    /**
     * 发送心跳/日志给所有 Follower
     */
    private void sendHeartbeats() {
        if (!running.get() || state != RaftState.LEADER) {
            return;
        }

        for (NodeId peer : cluster) {
            if (peer.equals(nodeId)) {
                continue;
            }
            sendAppendEntries(peer);
        }
    }

    /**
     * 向指定 Follower 发送 AppendEntries
     */
    private void sendAppendEntries(NodeId peer) {
        lock.lock();
        try {
            if (state != RaftState.LEADER) {
                return;
            }

            long peerNextIndex = nextIndex.getOrDefault(peer, 1L);
            long prevLogIndex = peerNextIndex - 1;
            long prevLogTerm = log.getTerm(prevLogIndex);
            List<LogEntry> entries = log.getFrom(peerNextIndex);

            AppendEntries request = new AppendEntries(
                    currentTerm,
                    nodeId,
                    prevLogIndex,
                    prevLogTerm,
                    entries,
                    commitIndex);

            rpcClient.sendAppendEntriesAsync(peer, request)
                    .thenAccept(response -> handleAppendEntriesResponse(peer, response, entries.size()));
        } finally {
            lock.unlock();
        }
    }

    /**
     * 处理 AppendEntries 响应
     */
    private void handleAppendEntriesResponse(NodeId peer, AppendEntriesResponse response, int entriesSent) {
        if (response == null) {
            return;
        }

        lock.lock();
        try {
            if (state != RaftState.LEADER) {
                return;
            }

            // 发现更高任期，转换为 Follower
            if (response.getTerm() > currentTerm) {
                becomeFollower(response.getTerm(), null);
                return;
            }

            if (response.isSuccess()) {
                // 更新 nextIndex 和 matchIndex
                long newMatchIndex = response.getMatchIndex();
                matchIndex.put(peer, newMatchIndex);
                nextIndex.put(peer, newMatchIndex + 1);

                // 尝试提交日志
                tryCommit();
            } else {
                // 日志不一致，回退 nextIndex 重试
                long expectedIndex = response.getMatchIndex();
                nextIndex.put(peer, Math.max(1, expectedIndex));

                // 立即重试
                sendAppendEntries(peer);
            }
        } finally {
            lock.unlock();
        }
    }

    /**
     * 尝试提交日志
     */
    private void tryCommit() {
        // 找到大多数节点已复制的最高索引
        long[] indices = new long[cluster.size()];
        int i = 0;
        for (NodeId peer : cluster) {
            if (peer.equals(nodeId)) {
                indices[i++] = log.getLastIndex();
            } else {
                indices[i++] = matchIndex.getOrDefault(peer, 0L);
            }
        }
        Arrays.sort(indices);

        // 取中位数（多数派都有的最高索引）
        long newCommitIndex = indices[indices.length - majority];

        // 只能提交当前任期的日志
        if (newCommitIndex > commitIndex && log.getTerm(newCommitIndex) == currentTerm) {
            logger.debug("{} advancing commit index from {} to {}", nodeId, commitIndex, newCommitIndex);
            commitIndex = newCommitIndex;
        }
    }

    // ==================== RPC 处理 ====================

    @Override
    public RequestVoteResponse handleRequestVote(RequestVote request) {
        lock.lock();
        try {
            logger.debug("{} received RequestVote: {}", nodeId, request);

            // 如果任期小于当前任期，拒绝投票
            if (request.getTerm() < currentTerm) {
                return new RequestVoteResponse(nodeId, currentTerm, false);
            }

            // 如果发现更高任期，转换为 Follower
            if (request.getTerm() > currentTerm) {
                becomeFollower(request.getTerm(), null);
            }

            // 检查是否可以投票
            boolean canVote = (votedFor == null || votedFor.equals(request.getCandidateId()));

            // 检查日志是否至少和自己一样新
            boolean logUpToDate = isLogUpToDate(request.getLastLogTerm(), request.getLastLogIndex());

            if (canVote && logUpToDate) {
                votedFor = request.getCandidateId();
                resetElectionTimeout();
                logger.info("{} voted for {} in term {}", nodeId, request.getCandidateId(), currentTerm);
                return new RequestVoteResponse(nodeId, currentTerm, true);
            }

            return new RequestVoteResponse(nodeId, currentTerm, false);
        } finally {
            lock.unlock();
        }
    }

    /**
     * 检查候选人日志是否至少和自己一样新
     */
    private boolean isLogUpToDate(long candidateLastTerm, long candidateLastIndex) {
        long myLastTerm = log.getLastTerm();
        long myLastIndex = log.getLastIndex();

        // 先比较任期，任期大的更新
        if (candidateLastTerm != myLastTerm) {
            return candidateLastTerm > myLastTerm;
        }
        // 任期相同，索引大的更新
        return candidateLastIndex >= myLastIndex;
    }

    @Override
    public AppendEntriesResponse handleAppendEntries(AppendEntries request) {
        lock.lock();
        try {
            logger.debug("{} received AppendEntries: {}", nodeId, request);

            // 如果任期小于当前任期，拒绝
            if (request.getTerm() < currentTerm) {
                return AppendEntriesResponse.fail(nodeId, currentTerm, log.getLastIndex());
            }

            // 如果任期大于等于当前任期，承认 Leader
            if (request.getTerm() >= currentTerm) {
                if (state != RaftState.FOLLOWER || request.getTerm() > currentTerm) {
                    becomeFollower(request.getTerm(), request.getLeaderId());
                } else {
                    leaderId = request.getLeaderId();
                    resetElectionTimeout();
                }
            }

            // 检查日志一致性
            if (!log.matchLog(request.getPrevLogIndex(), request.getPrevLogTerm())) {
                // 日志不匹配，返回期望的索引
                return AppendEntriesResponse.fail(nodeId, currentTerm, log.getLastIndex());
            }

            // 追加日志条目
            if (request.getEntries() != null && !request.getEntries().isEmpty()) {
                log.appendAll(request.getEntries());
            }

            // 更新 commitIndex
            if (request.getLeaderCommit() > commitIndex) {
                commitIndex = Math.min(request.getLeaderCommit(), log.getLastIndex());
            }

            return AppendEntriesResponse.success(nodeId, currentTerm, log.getLastIndex());
        } finally {
            lock.unlock();
        }
    }

    // ==================== 状态机应用 ====================

    /**
     * 启动日志应用循环
     */
    private void startApplyLoop() {
        Thread applyThread = new Thread(() -> {
            while (running.get()) {
                applyCommittedLogs();
                try {
                    Thread.sleep(10);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }, "raft-apply-" + nodeId.getNodeId());
        applyThread.setDaemon(true);
        applyThread.start();
    }

    /**
     * 应用已提交的日志到状态机
     */
    private void applyCommittedLogs() {
        while (lastApplied < commitIndex) {
            lastApplied++;
            LogEntry entry = log.get(lastApplied);
            if (entry != null) {
                Object result = stateMachine.apply(entry);
                logger.debug("{} applied log entry: {}, result: {}", nodeId, entry, result);

                // 通知等待的客户端
                CompletableFuture<Object> future = pendingCommands.remove(entry.getIndex());
                if (future != null) {
                    future.complete(result);
                }
            }
        }
    }

    // ==================== 客户端接口 ====================

    /**
     * 提交命令（仅 Leader 可调用）
     * 
     * @param command 命令
     * @return 命令执行结果的 Future
     */
    public CompletableFuture<Object> submitCommand(LogEntry.Command command) {
        lock.lock();
        try {
            if (state != RaftState.LEADER) {
                CompletableFuture<Object> future = new CompletableFuture<>();
                future.completeExceptionally(new IllegalStateException("Not leader. Current leader: " + leaderId));
                return future;
            }

            // 追加日志
            LogEntry entry = log.append(currentTerm, command);
            logger.info("{} appended command: {}", nodeId, entry);

            // 创建等待 Future
            CompletableFuture<Object> future = new CompletableFuture<>();
            pendingCommands.put(entry.getIndex(), future);

            // 立即复制给所有 Follower
            sendHeartbeats();

            return future;
        } finally {
            lock.unlock();
        }
    }

    // ==================== Getters ====================

    public NodeId getNodeId() {
        return nodeId;
    }

    public RaftState getState() {
        return state;
    }

    public long getCurrentTerm() {
        return currentTerm;
    }

    public NodeId getLeaderId() {
        return leaderId;
    }

    public boolean isLeader() {
        return state == RaftState.LEADER;
    }

    public long getCommitIndex() {
        return commitIndex;
    }

    public long getLastApplied() {
        return lastApplied;
    }

    public RaftLog getLog() {
        return log;
    }

    public StateMachine getStateMachine() {
        return stateMachine;
    }

    /**
     * 获取节点状态摘要
     */
    public String getStatusSummary() {
        return String.format("%s [%s] term=%d, leader=%s, log=%d, commit=%d, applied=%d",
                nodeId.getNodeId(),
                state,
                currentTerm,
                leaderId != null ? leaderId.getNodeId() : "null",
                log.getLastIndex(),
                commitIndex,
                lastApplied);
    }
}
