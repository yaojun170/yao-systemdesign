package cn.yj.sd.distributelock.db;

/**
 * 分布式锁异常
 * 用于封装锁操作过程中的各类异常
 * 
 * @author yaojun
 */
public class LockException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * 异常类型枚举
     */
    public enum ErrorType {
        /** 获取锁失败 */
        ACQUIRE_FAILED,
        /** 释放锁失败 */
        RELEASE_FAILED,
        /** 锁已过期 */
        LOCK_EXPIRED,
        /** 非锁持有者 */
        NOT_LOCK_OWNER,
        /** 数据库错误 */
        DATABASE_ERROR,
        /** 锁已存在 */
        LOCK_EXISTS,
        /** 锁不存在 */
        LOCK_NOT_EXISTS,
        /** 操作超时 */
        TIMEOUT
    }

    private final ErrorType errorType;

    public LockException(String message) {
        super(message);
        this.errorType = ErrorType.DATABASE_ERROR;
    }

    public LockException(String message, Throwable cause) {
        super(message, cause);
        this.errorType = ErrorType.DATABASE_ERROR;
    }

    public LockException(ErrorType errorType, String message) {
        super(message);
        this.errorType = errorType;
    }

    public LockException(ErrorType errorType, String message, Throwable cause) {
        super(message, cause);
        this.errorType = errorType;
    }

    public ErrorType getErrorType() {
        return errorType;
    }

    @Override
    public String toString() {
        return "LockException{" +
                "errorType=" + errorType +
                ", message='" + getMessage() + '\'' +
                '}';
    }
}
