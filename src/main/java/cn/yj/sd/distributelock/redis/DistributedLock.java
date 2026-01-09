package cn.yj.sd.distributelock.redis;

import java.util.concurrent.TimeUnit;

/**
 * 分布式锁接口
 * 定义分布式锁的核心操作
 * 
 * @author yaojun
 */
public interface DistributedLock {

    /**
     * 尝试获取锁（非阻塞）
     * 
     * @return true-获取成功，false-获取失败
     */
    boolean tryLock();

    /**
     * 尝试获取锁（带超时等待）
     * 
     * @param waitTime 最大等待时间
     * @param unit     时间单位
     * @return true-获取成功，false-超时
     */
    boolean tryLock(long waitTime, TimeUnit unit);

    /**
     * 释放锁
     * 
     * @return true-释放成功，false-释放失败（锁不存在或不属于当前持有者）
     */
    boolean unlock();

    /**
     * 检查锁是否被当前线程持有
     * 
     * @return true-当前线程持有锁
     */
    boolean isHeldByCurrentThread();

    /**
     * 获取锁名称
     * 
     * @return 锁名称
     */
    String getLockName();
}
