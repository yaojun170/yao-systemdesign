package cn.yj.sd.distributelock.zk;

import java.util.concurrent.TimeUnit;

/**
 * 分布式锁接口
 * 定义分布式锁的基本操作
 */
public interface DistributedLock {

    /**
     * 获取锁，阻塞直到成功
     * 
     * @throws Exception 获取锁过程中的异常
     */
    void lock() throws Exception;

    /**
     * 尝试获取锁，立即返回
     * 
     * @return 是否成功获取锁
     * @throws Exception 获取锁过程中的异常
     */
    boolean tryLock() throws Exception;

    /**
     * 尝试获取锁，带超时时间
     * 
     * @param timeout 超时时间
     * @param unit    时间单位
     * @return 是否成功获取锁
     * @throws Exception 获取锁过程中的异常
     */
    boolean tryLock(long timeout, TimeUnit unit) throws Exception;

    /**
     * 释放锁
     * 
     * @throws Exception 释放锁过程中的异常
     */
    void unlock() throws Exception;

    /**
     * 检查当前线程是否持有锁
     * 
     * @return 是否持有锁
     */
    boolean isHeldByCurrentThread();
}
