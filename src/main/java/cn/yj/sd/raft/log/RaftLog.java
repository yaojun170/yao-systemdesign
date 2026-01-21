package cn.yj.sd.raft.log;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Raft 日志管理器
 * 
 * 维护节点的日志条目列表，提供线程安全的日志操作：
 * - 追加日志
 * - 获取日志
 * - 截断日志（用于日志一致性修复）
 * 
 * 注意：日志索引从 1 开始，index=0 表示空日志
 */
public class RaftLog {

    /** 日志条目列表（索引从1开始，list.get(0) 对应 index=1） */
    private final List<LogEntry> entries;

    /** 读写锁，保证线程安全 */
    private final ReadWriteLock lock;

    public RaftLog() {
        this.entries = new ArrayList<>();
        this.lock = new ReentrantReadWriteLock();
    }

    /**
     * 追加新的日志条目
     * 
     * @param term    当前任期
     * @param command 命令
     * @return 新追加的日志条目
     */
    public LogEntry append(long term, LogEntry.Command command) {
        lock.writeLock().lock();
        try {
            long newIndex = entries.size() + 1;
            LogEntry entry = new LogEntry(newIndex, term, command);
            entries.add(entry);
            return entry;
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * 批量追加日志条目（用于 Follower 接收 Leader 的日志）
     * 
     * @param newEntries 要追加的日志条目
     */
    public void appendAll(List<LogEntry> newEntries) {
        if (newEntries == null || newEntries.isEmpty()) {
            return;
        }
        lock.writeLock().lock();
        try {
            for (LogEntry entry : newEntries) {
                // 如果索引位置已有条目，检查是否冲突
                int listIndex = (int) (entry.getIndex() - 1);
                if (listIndex < entries.size()) {
                    LogEntry existing = entries.get(listIndex);
                    if (existing.getTerm() != entry.getTerm()) {
                        // 冲突：截断从此位置开始的所有条目
                        truncateFrom(entry.getIndex());
                        entries.add(entry);
                    }
                    // 如果 term 相同，说明已存在相同条目，跳过
                } else {
                    entries.add(entry);
                }
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * 获取指定索引的日志条目
     * 
     * @param index 日志索引（从1开始）
     * @return 日志条目，不存在返回 null
     */
    public LogEntry get(long index) {
        lock.readLock().lock();
        try {
            if (index < 1 || index > entries.size()) {
                return null;
            }
            return entries.get((int) (index - 1));
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * 获取从指定索引开始的所有日志条目
     * 
     * @param startIndex 起始索引（包含）
     * @return 日志条目列表
     */
    public List<LogEntry> getFrom(long startIndex) {
        lock.readLock().lock();
        try {
            if (startIndex < 1 || startIndex > entries.size()) {
                return Collections.emptyList();
            }
            return new ArrayList<>(entries.subList((int) (startIndex - 1), entries.size()));
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * 获取最后一个日志条目的索引
     * 
     * @return 最后日志索引，空日志返回 0
     */
    public long getLastIndex() {
        lock.readLock().lock();
        try {
            return entries.size();
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * 获取最后一个日志条目的任期
     * 
     * @return 最后日志任期，空日志返回 0
     */
    public long getLastTerm() {
        lock.readLock().lock();
        try {
            if (entries.isEmpty()) {
                return 0;
            }
            return entries.get(entries.size() - 1).getTerm();
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * 获取指定索引的日志任期
     * 
     * @param index 日志索引
     * @return 任期号，索引无效返回 0
     */
    public long getTerm(long index) {
        lock.readLock().lock();
        try {
            if (index < 1 || index > entries.size()) {
                return 0;
            }
            return entries.get((int) (index - 1)).getTerm();
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * 从指定索引开始截断日志（包含该索引）
     * 
     * @param fromIndex 起始截断索引
     */
    public void truncateFrom(long fromIndex) {
        lock.writeLock().lock();
        try {
            if (fromIndex < 1 || fromIndex > entries.size()) {
                return;
            }
            // subList 返回的是视图，需要手动删除
            while (entries.size() >= fromIndex) {
                entries.remove(entries.size() - 1);
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * 检查日志匹配性
     * 
     * @param prevLogIndex 前一条日志索引
     * @param prevLogTerm  前一条日志任期
     * @return 是否匹配
     */
    public boolean matchLog(long prevLogIndex, long prevLogTerm) {
        lock.readLock().lock();
        try {
            // 空日志检查
            if (prevLogIndex == 0) {
                return true;
            }
            // 索引超出范围
            if (prevLogIndex > entries.size()) {
                return false;
            }
            // 检查任期是否匹配
            return entries.get((int) (prevLogIndex - 1)).getTerm() == prevLogTerm;
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * 获取日志大小
     */
    public int size() {
        lock.readLock().lock();
        try {
            return entries.size();
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public String toString() {
        lock.readLock().lock();
        try {
            return String.format("RaftLog{size=%d, lastIndex=%d, lastTerm=%d}",
                    entries.size(), getLastIndex(), getLastTerm());
        } finally {
            lock.readLock().unlock();
        }
    }
}
