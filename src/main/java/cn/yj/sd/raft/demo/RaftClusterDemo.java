package cn.yj.sd.raft.demo;

import cn.yj.sd.raft.core.*;
import cn.yj.sd.raft.log.LogEntry;
import cn.yj.sd.raft.log.StateMachine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.*;

/**
 * Raft 集群演示
 * 
 * 演示内容：
 * 1. 启动 3 节点 Raft 集群
 * 2. 观察 Leader 选举过程
 * 3. 通过 Leader 提交命令
 * 4. 观察日志复制
 * 5. 模拟 Leader 故障和重新选举
 */
public class RaftClusterDemo {

    private static final Logger logger = LoggerFactory.getLogger(RaftClusterDemo.class);

    private final List<RaftNode> nodes;
    private final List<NodeId> cluster;

    public RaftClusterDemo(int nodeCount, int basePort) {
        this.cluster = new ArrayList<>();
        this.nodes = new ArrayList<>();

        // 创建节点 ID 列表
        for (int i = 0; i < nodeCount; i++) {
            cluster.add(new NodeId("node-" + i, "localhost", basePort + i));
        }

        // 创建节点
        for (NodeId nodeId : cluster) {
            nodes.add(new RaftNode(nodeId, cluster));
        }
    }

    /**
     * 启动所有节点
     */
    public void startAll() throws Exception {
        logger.info("========== Starting Raft Cluster ==========");
        for (RaftNode node : nodes) {
            node.start();
            // 稍微错开启动时间
            Thread.sleep(100);
        }
        logger.info("========== All nodes started ==========");
    }

    /**
     * 停止所有节点
     */
    public void stopAll() {
        logger.info("========== Stopping Raft Cluster ==========");
        for (RaftNode node : nodes) {
            node.stop();
        }
        logger.info("========== All nodes stopped ==========");
    }

    /**
     * 等待 Leader 选举完成
     */
    public RaftNode waitForLeader(long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            for (RaftNode node : nodes) {
                if (node.isLeader()) {
                    logger.info("Leader elected: {}", node.getNodeId());
                    return node;
                }
            }
            Thread.sleep(100);
        }
        throw new RuntimeException("No leader elected within timeout");
    }

    /**
     * 获取当前 Leader
     */
    public RaftNode getLeader() {
        for (RaftNode node : nodes) {
            if (node.isLeader()) {
                return node;
            }
        }
        return null;
    }

    /**
     * 打印集群状态
     */
    public void printStatus() {
        System.out.println("\n========== Cluster Status ==========");
        for (RaftNode node : nodes) {
            System.out.println(node.getStatusSummary());
        }
        System.out.println("=====================================\n");
    }

    /**
     * 停止指定节点（模拟故障）
     */
    public void stopNode(int index) {
        if (index >= 0 && index < nodes.size()) {
            RaftNode node = nodes.get(index);
            logger.info("Stopping node: {}", node.getNodeId());
            node.stop();
        }
    }

    /**
     * 重启指定节点
     */
    public void restartNode(int index) throws Exception {
        if (index >= 0 && index < nodes.size()) {
            NodeId nodeId = cluster.get(index);
            RaftNode newNode = new RaftNode(nodeId, cluster);
            nodes.set(index, newNode);
            newNode.start();
            logger.info("Restarted node: {}", nodeId);
        }
    }

    public List<RaftNode> getNodes() {
        return nodes;
    }

    // ==================== Main 方法 ====================

    public static void main(String[] args) throws Exception {
        System.out.println("╔══════════════════════════════════════════════════════════════╗");
        System.out.println("║                    Raft 协议演示程序                          ║");
        System.out.println("╠══════════════════════════════════════════════════════════════╣");
        System.out.println("║  本演示将：                                                   ║");
        System.out.println("║  1. 启动 3 节点 Raft 集群                                     ║");
        System.out.println("║  2. 等待 Leader 选举                                          ║");
        System.out.println("║  3. 提交几条命令到状态机                                       ║");
        System.out.println("║  4. 模拟 Leader 故障并观察重新选举                             ║");
        System.out.println("╚══════════════════════════════════════════════════════════════╝");
        System.out.println();

        RaftClusterDemo demo = new RaftClusterDemo(3, 9000);

        try {
            // 1. 启动集群
            demo.startAll();
            Thread.sleep(500);

            // 2. 等待 Leader 选举
            System.out.println("\n>>> 等待 Leader 选举...");
            RaftNode leader = demo.waitForLeader(5000);
            demo.printStatus();

            // 3. 提交命令
            System.out.println("\n>>> 通过 Leader 提交命令...");
            submitAndWait(leader, new LogEntry.SetCommand("name", "Raft"));
            submitAndWait(leader, new LogEntry.SetCommand("version", "1.0"));
            submitAndWait(leader, new LogEntry.SetCommand("author", "Ongaro"));

            // 等待日志复制和应用
            Thread.sleep(500);
            demo.printStatus();

            // 打印状态机内容
            System.out.println(">>> 状态机内容：");
            for (RaftNode node : demo.getNodes()) {
                StateMachine.KVStateMachine sm = (StateMachine.KVStateMachine) node.getStateMachine();
                System.out.printf("  %s: %s%n", node.getNodeId().getNodeId(), sm.getAll());
            }

            // 4. 模拟 Leader 故障
            System.out.println("\n>>> 模拟 Leader 故障...");
            int leaderIndex = -1;
            for (int i = 0; i < demo.getNodes().size(); i++) {
                if (demo.getNodes().get(i).isLeader()) {
                    leaderIndex = i;
                    break;
                }
            }
            demo.stopNode(leaderIndex);

            // 等待重新选举
            System.out.println("\n>>> 等待新 Leader 选举...");
            Thread.sleep(2000);

            RaftNode newLeader = null;
            for (RaftNode node : demo.getNodes()) {
                if (node.isLeader()) {
                    newLeader = node;
                    break;
                }
            }

            if (newLeader != null) {
                System.out.println(">>> 新 Leader: " + newLeader.getNodeId());
                demo.printStatus();

                // 通过新 Leader 提交命令
                System.out.println("\n>>> 通过新 Leader 提交命令...");
                submitAndWait(newLeader, new LogEntry.SetCommand("status", "recovered"));

                Thread.sleep(500);
                demo.printStatus();
            }

            System.out.println("\n>>> 演示完成！按 Enter 退出...");
            System.in.read();

        } finally {
            demo.stopAll();
        }
    }

    /**
     * 提交命令并等待结果
     */
    private static void submitAndWait(RaftNode leader, LogEntry.Command command) {
        try {
            CompletableFuture<Object> future = leader.submitCommand(command);
            Object result = future.get(5, TimeUnit.SECONDS);
            System.out.printf("  命令 [%s] 提交成功，结果: %s%n", command, result);
        } catch (Exception e) {
            System.out.printf("  命令 [%s] 提交失败: %s%n", command, e.getMessage());
        }
    }
}
