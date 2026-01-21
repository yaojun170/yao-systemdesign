package cn.yj.sd.raft.rpc;

import cn.yj.sd.raft.core.NodeId;
import cn.yj.sd.raft.rpc.message.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Raft RPC 服务端
 * 
 * 监听指定端口，接收并处理来自其他节点的 RPC 请求
 */
public class RaftRpcServer {

    private static final Logger logger = LoggerFactory.getLogger(RaftRpcServer.class);

    private final NodeId nodeId;
    private final RaftRpcService rpcService;
    private final ExecutorService executor;
    private final AtomicBoolean running;

    private ServerSocket serverSocket;
    private Thread acceptThread;

    public RaftRpcServer(NodeId nodeId, RaftRpcService rpcService) {
        this.nodeId = nodeId;
        this.rpcService = rpcService;
        this.executor = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "raft-rpc-handler");
            t.setDaemon(true);
            return t;
        });
        this.running = new AtomicBoolean(false);
    }

    /**
     * 启动 RPC 服务
     */
    public void start() throws IOException {
        if (running.compareAndSet(false, true)) {
            serverSocket = new ServerSocket(nodeId.getPort());
            logger.info("{} RPC server started on port {}", nodeId.getNodeId(), nodeId.getPort());

            acceptThread = new Thread(this::acceptLoop, "raft-rpc-accept-" + nodeId.getNodeId());
            acceptThread.setDaemon(true);
            acceptThread.start();
        }
    }

    /**
     * 接受连接的主循环
     */
    private void acceptLoop() {
        while (running.get()) {
            try {
                Socket clientSocket = serverSocket.accept();
                executor.submit(() -> handleConnection(clientSocket));
            } catch (IOException e) {
                if (running.get()) {
                    logger.error("Error accepting connection", e);
                }
            }
        }
    }

    /**
     * 处理单个连接
     */
    private void handleConnection(Socket socket) {
        try (socket) {
            socket.setSoTimeout(5000);

            ObjectInputStream in = new ObjectInputStream(socket.getInputStream());
            ObjectOutputStream out = new ObjectOutputStream(socket.getOutputStream());

            Object request = in.readObject();
            Object response = processRequest(request);

            if (response != null) {
                out.writeObject(response);
                out.flush();
            }
        } catch (IOException | ClassNotFoundException e) {
            logger.debug("Error handling RPC connection: {}", e.getMessage());
        }
    }

    /**
     * 处理 RPC 请求
     */
    private Object processRequest(Object request) {
        if (request instanceof RequestVote) {
            return rpcService.handleRequestVote((RequestVote) request);
        } else if (request instanceof AppendEntries) {
            return rpcService.handleAppendEntries((AppendEntries) request);
        } else {
            logger.warn("Unknown request type: {}", request.getClass().getSimpleName());
            return null;
        }
    }

    /**
     * 停止 RPC 服务
     */
    public void stop() {
        if (running.compareAndSet(true, false)) {
            try {
                if (serverSocket != null && !serverSocket.isClosed()) {
                    serverSocket.close();
                }
            } catch (IOException e) {
                logger.error("Error closing server socket", e);
            }

            executor.shutdown();

            if (acceptThread != null) {
                acceptThread.interrupt();
            }

            logger.info("{} RPC server stopped", nodeId.getNodeId());
        }
    }

    public boolean isRunning() {
        return running.get();
    }
}
