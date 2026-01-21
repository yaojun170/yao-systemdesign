package cn.yj.sd.raft.log;

import java.io.Serializable;

/**
 * Raft 日志条目
 * 
 * 每个日志条目包含：
 * - index: 日志索引（从1开始）
 * - term: 创建该条目时的任期号
 * - command: 要应用到状态机的命令
 */
public class LogEntry implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 日志索引，从1开始 */
    private final long index;

    /** 创建该条目时的任期号 */
    private final long term;

    /** 要应用到状态机的命令 */
    private final Command command;

    public LogEntry(long index, long term, Command command) {
        this.index = index;
        this.term = term;
        this.command = command;
    }

    public long getIndex() {
        return index;
    }

    public long getTerm() {
        return term;
    }

    public Command getCommand() {
        return command;
    }

    @Override
    public String toString() {
        return String.format("LogEntry{index=%d, term=%d, command=%s}", index, term, command);
    }

    /**
     * 命令接口 - 表示要在状态机上执行的操作
     */
    public interface Command extends Serializable {
        /**
         * 获取命令类型
         */
        String getType();
    }

    /**
     * 空操作命令 - 用于 Leader 刚当选时的占位
     */
    public static class NoOpCommand implements Command {
        private static final long serialVersionUID = 1L;

        @Override
        public String getType() {
            return "NO_OP";
        }

        @Override
        public String toString() {
            return "NoOp";
        }
    }

    /**
     * KV 设置命令
     */
    public static class SetCommand implements Command {
        private static final long serialVersionUID = 1L;

        private final String key;
        private final String value;

        public SetCommand(String key, String value) {
            this.key = key;
            this.value = value;
        }

        public String getKey() {
            return key;
        }

        public String getValue() {
            return value;
        }

        @Override
        public String getType() {
            return "SET";
        }

        @Override
        public String toString() {
            return String.format("SET %s=%s", key, value);
        }
    }

    /**
     * KV 删除命令
     */
    public static class DeleteCommand implements Command {
        private static final long serialVersionUID = 1L;

        private final String key;

        public DeleteCommand(String key) {
            this.key = key;
        }

        public String getKey() {
            return key;
        }

        @Override
        public String getType() {
            return "DELETE";
        }

        @Override
        public String toString() {
            return String.format("DELETE %s", key);
        }
    }
}
