package cn.yj.sd.raft.rpc;

import cn.yj.sd.raft.core.NodeId;
import cn.yj.sd.raft.rpc.message.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.concurrent.*;

/**
 * Raft RPC 客户端
 * 
 * 用于向其他 Raft 节点发送 RPC 请求
 * 使用 Java 原生 Socket 和 ObjectStream 实现简单的 RPC
 */
public class RaftRpcClient {

    private static final Logger logger = LoggerFactory.getLogger(RaftRpcClient.class);

    /** 连接超时（毫秒） */
    private static final int CONNECT_TIMEOUT = 500;

    /** 读取超时（毫秒） */
    private static final int READ_TIMEOUT = 1000;

    /** 异步 RPC 线程池 */
    private final ExecutorService executor;

    public RaftRpcClient() {
        this.executor = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "raft-rpc-client");
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * 发送 RequestVote RPC（同步）
     */
    public RequestVoteResponse sendRequestVote(NodeId target, RequestVote request) {
        return sendRpc(target, request, RequestVoteResponse.class);
    }

    /**
     * 发送 RequestVote RPC（异步）
     */
    public CompletableFuture<RequestVoteResponse> sendRequestVoteAsync(NodeId target, RequestVote request) {
        return CompletableFuture.supplyAsync(() -> sendRequestVote(target, request), executor);
    }

    /**
     * 发送 AppendEntries RPC（同步）
     */
    public AppendEntriesResponse sendAppendEntries(NodeId target, AppendEntries request) {
        return sendRpc(target, request, AppendEntriesResponse.class);
    }

    /**
     * 发送 AppendEntries RPC（异步）
     */
    public CompletableFuture<AppendEntriesResponse> sendAppendEntriesAsync(NodeId target, AppendEntries request) {
        return CompletableFuture.supplyAsync(() -> sendAppendEntries(target, request), executor);
    }

    /**
     * 通用 RPC 发送方法
     */
    @SuppressWarnings("unchecked")
    private <T> T sendRpc(NodeId target, Object request, Class<T> responseType) {
        try (Socket socket = new Socket()) {
            // 设置超时
            socket.connect(new java.net.InetSocketAddress(target.getHost(), target.getPort()), CONNECT_TIMEOUT);
            socket.setSoTimeout(READ_TIMEOUT);

            // 发送请求
            ObjectOutputStream out = new ObjectOutputStream(socket.getOutputStream());
            out.writeObject(request);
            out.flush();

            // 接收响应
            ObjectInputStream in = new ObjectInputStream(socket.getInputStream());
            Object response = in.readObject();

            if (responseType.isInstance(response)) {
                return (T) response;
            } else {
                logger.warn("Unexpected response type from {}: expected={}, actual={}",
                        target, responseType.getSimpleName(), response.getClass().getSimpleName());
                return null;
            }
        } catch (SocketTimeoutException e) {
            logger.debug("RPC timeout to {}", target);
            return null;
        } catch (IOException e) {
            logger.debug("RPC failed to {}: {}", target, e.getMessage());
            return null;
        } catch (ClassNotFoundException e) {
            logger.error("Failed to deserialize response from {}", target, e);
            return null;
        }
    }

    /**
     * 关闭客户端
     */
    public void shutdown() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
