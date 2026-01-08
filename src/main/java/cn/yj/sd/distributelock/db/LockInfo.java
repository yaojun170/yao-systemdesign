package cn.yj.sd.distributelock.db;

import java.time.LocalDateTime;

/**
 * 锁信息实体类
 * 对应数据库中的distributed_lock表
 * 
 * @author yaojun
 */
public class LockInfo {

    /** 主键ID */
    private Long id;

    /** 锁名称(业务唯一标识) */
    private String lockName;

    /** 锁值(持有者标识，通常是UUID或机器ID+线程ID) */
    private String lockValue;

    /** 过期时间 */
    private LocalDateTime expireTime;

    /** 版本号(用于乐观锁) */
    private Integer version;

    /** 重入次数 */
    private Integer reentrantCount;

    /** 创建时间 */
    private LocalDateTime createTime;

    /** 更新时间 */
    private LocalDateTime updateTime;

    public LockInfo() {
    }

    public LockInfo(String lockName, String lockValue, LocalDateTime expireTime) {
        this.lockName = lockName;
        this.lockValue = lockValue;
        this.expireTime = expireTime;
        this.version = 0;
        this.reentrantCount = 1;
    }

    // ==================== Builder模式 ====================

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private final LockInfo lockInfo = new LockInfo();

        public Builder id(Long id) {
            lockInfo.id = id;
            return this;
        }

        public Builder lockName(String lockName) {
            lockInfo.lockName = lockName;
            return this;
        }

        public Builder lockValue(String lockValue) {
            lockInfo.lockValue = lockValue;
            return this;
        }

        public Builder expireTime(LocalDateTime expireTime) {
            lockInfo.expireTime = expireTime;
            return this;
        }

        public Builder version(Integer version) {
            lockInfo.version = version;
            return this;
        }

        public Builder reentrantCount(Integer reentrantCount) {
            lockInfo.reentrantCount = reentrantCount;
            return this;
        }

        public LockInfo build() {
            return lockInfo;
        }
    }

    // ==================== 判断方法 ====================

    /**
     * 判断锁是否已过期
     */
    public boolean isExpired() {
        return expireTime != null && LocalDateTime.now().isAfter(expireTime);
    }

    /**
     * 判断是否由指定持有者持有
     */
    public boolean isHeldBy(String holderValue) {
        return lockValue != null && lockValue.equals(holderValue);
    }

    // ==================== Getters and Setters ====================

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getLockName() {
        return lockName;
    }

    public void setLockName(String lockName) {
        this.lockName = lockName;
    }

    public String getLockValue() {
        return lockValue;
    }

    public void setLockValue(String lockValue) {
        this.lockValue = lockValue;
    }

    public LocalDateTime getExpireTime() {
        return expireTime;
    }

    public void setExpireTime(LocalDateTime expireTime) {
        this.expireTime = expireTime;
    }

    public Integer getVersion() {
        return version;
    }

    public void setVersion(Integer version) {
        this.version = version;
    }

    public Integer getReentrantCount() {
        return reentrantCount;
    }

    public void setReentrantCount(Integer reentrantCount) {
        this.reentrantCount = reentrantCount;
    }

    public LocalDateTime getCreateTime() {
        return createTime;
    }

    public void setCreateTime(LocalDateTime createTime) {
        this.createTime = createTime;
    }

    public LocalDateTime getUpdateTime() {
        return updateTime;
    }

    public void setUpdateTime(LocalDateTime updateTime) {
        this.updateTime = updateTime;
    }

    @Override
    public String toString() {
        return "LockInfo{" +
                "id=" + id +
                ", lockName='" + lockName + '\'' +
                ", lockValue='" + lockValue + '\'' +
                ", expireTime=" + expireTime +
                ", version=" + version +
                ", reentrantCount=" + reentrantCount +
                '}';
    }
}
