package cn.yj.sd.raft.log;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 状态机接口
 * 
 * Raft 本身只保证日志的一致性，状态机负责具体的业务逻辑。
 * 当日志被提交后，会应用到状态机上。
 */
public interface StateMachine {

    /**
     * 应用日志条目到状态机
     * 
     * @param entry 已提交的日志条目
     * @return 应用结果
     */
    Object apply(LogEntry entry);

    /**
     * 获取状态机数据（用于快照等场景）
     */
    Map<String, Object> getState();

    /**
     * 简单的 KV 状态机实现
     */
    class KVStateMachine implements StateMachine {

        private final Map<String, String> data = new ConcurrentHashMap<>();

        @Override
        public Object apply(LogEntry entry) {
            LogEntry.Command command = entry.getCommand();

            if (command instanceof LogEntry.SetCommand) {
                LogEntry.SetCommand setCmd = (LogEntry.SetCommand) command;
                String oldValue = data.put(setCmd.getKey(), setCmd.getValue());
                return oldValue;
            } else if (command instanceof LogEntry.DeleteCommand) {
                LogEntry.DeleteCommand delCmd = (LogEntry.DeleteCommand) command;
                String removedValue = data.remove(delCmd.getKey());
                return removedValue;
            } else if (command instanceof LogEntry.NoOpCommand) {
                // NoOp 不做任何操作
                return null;
            }

            throw new IllegalArgumentException("Unknown command type: " + command.getType());
        }

        @Override
        public Map<String, Object> getState() {
            return new ConcurrentHashMap<>(data);
        }

        /**
         * 获取指定 key 的值
         */
        public String get(String key) {
            return data.get(key);
        }

        /**
         * 获取所有数据
         */
        public Map<String, String> getAll() {
            return new ConcurrentHashMap<>(data);
        }

        @Override
        public String toString() {
            return "KVStateMachine{data=" + data + "}";
        }
    }
}
