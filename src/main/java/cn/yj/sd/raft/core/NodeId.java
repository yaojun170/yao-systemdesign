package cn.yj.sd.raft.core;

import java.io.Serializable;
import java.util.Objects;

/**
 * Raft 节点唯一标识
 * 
 * 包含节点的网络地址信息，用于节点间通信
 */
public class NodeId implements Serializable {

    private static final long serialVersionUID = 1L;

    private final String nodeId;
    private final String host;
    private final int port;

    public NodeId(String nodeId, String host, int port) {
        this.nodeId = nodeId;
        this.host = host;
        this.port = port;
    }

    public String getNodeId() {
        return nodeId;
    }

    public String getHost() {
        return host;
    }

    public int getPort() {
        return port;
    }

    /**
     * 获取节点地址字符串
     */
    public String getAddress() {
        return host + ":" + port;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o)
            return true;
        if (o == null || getClass() != o.getClass())
            return false;
        NodeId that = (NodeId) o;
        return Objects.equals(nodeId, that.nodeId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(nodeId);
    }

    @Override
    public String toString() {
        return String.format("Node[%s@%s:%d]", nodeId, host, port);
    }
}
