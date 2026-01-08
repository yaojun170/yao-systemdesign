package cn.yj.sd.distributelock.db;

import java.util.concurrent.TimeUnit;

/**
 * 分布式锁接口
 * 定义分布式锁的核心操作规范
 * 
 * @author yaojun
 */
public interface DistributedLock {

    /**
     * 阻塞式获取锁
     * 如果锁已被其他客户端持有，则一直等待直到获取成功
     * 
     * @throws LockException 获取锁异常
     */
    void lock() throws LockException;

    /**
     * 阻塞式获取锁，支持超时
     * 
     * @param timeout 超时时间
     * @param unit    时间单位
     * @return true-获取成功，false-超时未获取到
     * @throws LockException 获取锁异常
     */
    boolean lock(long timeout, TimeUnit unit) throws LockException;

    /**
     * 非阻塞式尝试获取锁
     * 立即返回获取结果，不等待
     * 
     * @return true-获取成功，false-获取失败
     * @throws LockException 获取锁异常
     */
    boolean tryLock() throws LockException;

    /**
     * 带超时的尝试获取锁
     * 在指定时间内尝试获取锁
     * 
     * @param timeout 超时时间
     * @param unit    时间单位
     * @return true-获取成功，false-超时
     * @throws LockException 获取锁异常
     */
    boolean tryLock(long timeout, TimeUnit unit) throws LockException;

    /**
     * 释放锁
     * 只有锁的持有者才能释放锁
     * 
     * @throws LockException 释放锁异常
     */
    void unlock() throws LockException;

    /**
     * 检查当前是否持有锁
     * 
     * @return true-持有锁，false-未持有锁
     */
    boolean isLocked();

    /**
     * 检查是否被当前线程/客户端持有
     * 
     * @return true-被当前持有者持有
     */
    boolean isHeldByCurrentHolder();

    /**
     * 获取锁名称
     * 
     * @return 锁名称
     */
    String getLockName();
}
