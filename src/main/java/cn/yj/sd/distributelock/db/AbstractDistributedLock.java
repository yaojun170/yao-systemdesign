package cn.yj.sd.distributelock.db;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * 分布式锁抽象基类
 * 提供通用的锁操作模板和工具方法
 * 
 * @author yaojun
 */
public abstract class AbstractDistributedLock implements DistributedLock {

    /** 锁名称 */
    protected final String lockName;

    /** 锁值（唯一标识当前持有者） */
    protected final String lockValue;

    /** 锁的默认过期时间（秒） */
    protected final long expireSeconds;

    /** 数据访问层 */
    protected final LockRepository lockRepository;

    /** 重试间隔（毫秒） */
    protected static final long RETRY_INTERVAL_MS = 100;

    /** 默认过期时间30秒 */
    protected static final long DEFAULT_EXPIRE_SECONDS = 30;

    /**
     * 构造函数
     * 
     * @param lockName       锁名称
     * @param lockRepository 数据访问层
     */
    public AbstractDistributedLock(String lockName, LockRepository lockRepository) {
        this(lockName, generateLockValue(), DEFAULT_EXPIRE_SECONDS, lockRepository);
    }

    /**
     * 构造函数
     * 
     * @param lockName       锁名称
     * @param expireSeconds  过期时间（秒）
     * @param lockRepository 数据访问层
     */
    public AbstractDistributedLock(String lockName, long expireSeconds, LockRepository lockRepository) {
        this(lockName, generateLockValue(), expireSeconds, lockRepository);
    }

    /**
     * 构造函数
     * 
     * @param lockName       锁名称
     * @param lockValue      锁值
     * @param expireSeconds  过期时间（秒）
     * @param lockRepository 数据访问层
     */
    public AbstractDistributedLock(String lockName, String lockValue, long expireSeconds,
            LockRepository lockRepository) {
        if (lockName == null || lockName.trim().isEmpty()) {
            throw new IllegalArgumentException("Lock name cannot be null or empty");
        }
        if (lockValue == null || lockValue.trim().isEmpty()) {
            throw new IllegalArgumentException("Lock value cannot be null or empty");
        }
        if (expireSeconds <= 0) {
            throw new IllegalArgumentException("Expire seconds must be positive");
        }
        if (lockRepository == null) {
            throw new IllegalArgumentException("Lock repository cannot be null");
        }

        this.lockName = lockName;
        this.lockValue = lockValue;
        this.expireSeconds = expireSeconds;
        this.lockRepository = lockRepository;
    }

    /**
     * 生成唯一的锁值
     * 格式：UUID + 机器标识 + 线程ID
     */
    protected static String generateLockValue() {
        return UUID.randomUUID().toString().replace("-", "") +
                ":" + getHostIdentifier() +
                ":" + Thread.currentThread().getId();
    }

    /**
     * 获取主机标识
     */
    private static String getHostIdentifier() {
        try {
            return java.net.InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            return "unknown";
        }
    }

    @Override
    public void lock() throws LockException {
        lock(Long.MAX_VALUE, TimeUnit.MILLISECONDS);
    }

    @Override
    public boolean lock(long timeout, TimeUnit unit) throws LockException {
        long startTime = System.currentTimeMillis();
        long timeoutMs = unit.toMillis(timeout);

        while (true) {
            if (tryLock()) {
                return true;
            }

            // 检查是否超时
            if (System.currentTimeMillis() - startTime >= timeoutMs) {
                return false;
            }

            // 等待一段时间后重试
            try {
                Thread.sleep(RETRY_INTERVAL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new LockException(LockException.ErrorType.ACQUIRE_FAILED,
                        "Lock acquisition interrupted");
            }
        }
    }

    @Override
    public boolean tryLock(long timeout, TimeUnit unit) throws LockException {
        return lock(timeout, unit);
    }

    @Override
    public boolean isLocked() {
        return lockRepository.findByLockName(lockName)
                .map(info -> !info.isExpired())
                .orElse(false);
    }

    @Override
    public boolean isHeldByCurrentHolder() {
        return lockRepository.findByLockName(lockName)
                .map(info -> info.isHeldBy(lockValue) && !info.isExpired())
                .orElse(false);
    }

    @Override
    public String getLockName() {
        return lockName;
    }

    /**
     * 获取锁值
     */
    public String getLockValue() {
        return lockValue;
    }

    /**
     * 获取过期时间（秒）
     */
    public long getExpireSeconds() {
        return expireSeconds;
    }
}
