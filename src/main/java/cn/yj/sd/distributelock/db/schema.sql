-- ============================================================
-- MySQL分布式锁 - 数据库表结构
-- ============================================================

-- 创建数据库（如果需要）
-- CREATE DATABASE IF NOT EXISTS test_db DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
-- USE test_db;

-- 删除已存在的表（开发环境使用）
-- DROP TABLE IF EXISTS distributed_lock;

-- ============================================================
-- 分布式锁表
-- ============================================================
CREATE TABLE IF NOT EXISTS distributed_lock (
    -- 主键
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键ID',
    
    -- 锁名称（业务唯一标识）
    lock_name VARCHAR(128) NOT NULL COMMENT '锁名称，如：order:create:123',
    
    -- 锁值（持有者标识）
    -- 格式：UUID:hostname:threadId
    lock_value VARCHAR(128) NOT NULL COMMENT '锁值，用于标识锁的持有者',
    
    -- 过期时间
    expire_time DATETIME NOT NULL COMMENT '锁的过期时间',
    
    -- 版本号（乐观锁使用）
    version INT DEFAULT 0 COMMENT '版本号，用于乐观锁CAS操作',
    
    -- 重入次数
    reentrant_count INT DEFAULT 1 COMMENT '重入次数，支持可重入锁',
    
    -- 审计字段
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    
    -- 唯一索引（保证锁的唯一性）
    UNIQUE KEY uk_lock_name (lock_name),
    
    -- 过期时间索引（用于清理过期锁）
    KEY idx_expire_time (expire_time)
    
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='分布式锁表';


-- ============================================================
-- 存储过程：获取唯一索引锁
-- ============================================================
DELIMITER //

CREATE PROCEDURE sp_acquire_unique_lock(
    IN p_lock_name VARCHAR(128),
    IN p_lock_value VARCHAR(128),
    IN p_expire_seconds INT,
    OUT p_result INT -- 1:成功, 0:失败, 2:重入
)
BEGIN
    DECLARE v_existing_value VARCHAR(128);
    DECLARE v_expire_time DATETIME;
    DECLARE v_reentrant_count INT;
    
    -- 查询现有锁
    SELECT lock_value, expire_time, reentrant_count 
    INTO v_existing_value, v_expire_time, v_reentrant_count
    FROM distributed_lock 
    WHERE lock_name = p_lock_name;
    
    IF v_existing_value IS NULL THEN
        -- 锁不存在，尝试插入
        INSERT INTO distributed_lock (lock_name, lock_value, expire_time, version, reentrant_count)
        VALUES (p_lock_name, p_lock_value, DATE_ADD(NOW(), INTERVAL p_expire_seconds SECOND), 0, 1);
        SET p_result = 1;
        
    ELSEIF v_existing_value = p_lock_value THEN
        -- 重入
        IF v_expire_time > NOW() THEN
            UPDATE distributed_lock 
            SET reentrant_count = reentrant_count + 1 
            WHERE lock_name = p_lock_name;
            SET p_result = 2;
        ELSE
            -- 锁已过期，重新获取
            UPDATE distributed_lock 
            SET lock_value = p_lock_value,
                expire_time = DATE_ADD(NOW(), INTERVAL p_expire_seconds SECOND),
                reentrant_count = 1
            WHERE lock_name = p_lock_name;
            SET p_result = 1;
        END IF;
        
    ELSEIF v_expire_time <= NOW() THEN
        -- 锁已过期，抢占
        UPDATE distributed_lock 
        SET lock_value = p_lock_value,
            expire_time = DATE_ADD(NOW(), INTERVAL p_expire_seconds SECOND),
            reentrant_count = 1
        WHERE lock_name = p_lock_name AND expire_time <= NOW();
        
        IF ROW_COUNT() > 0 THEN
            SET p_result = 1;
        ELSE
            SET p_result = 0;
        END IF;
        
    ELSE
        -- 锁被其他持有者持有
        SET p_result = 0;
    END IF;
    
END //

DELIMITER ;


-- ============================================================
-- 存储过程：释放锁
-- ============================================================
DELIMITER //

CREATE PROCEDURE sp_release_lock(
    IN p_lock_name VARCHAR(128),
    IN p_lock_value VARCHAR(128),
    OUT p_result INT -- 1:成功释放, 2:减少重入, 0:失败
)
BEGIN
    DECLARE v_reentrant_count INT;
    
    -- 查询重入次数
    SELECT reentrant_count INTO v_reentrant_count
    FROM distributed_lock 
    WHERE lock_name = p_lock_name AND lock_value = p_lock_value;
    
    IF v_reentrant_count IS NULL THEN
        SET p_result = 0;
    ELSEIF v_reentrant_count > 1 THEN
        -- 减少重入次数
        UPDATE distributed_lock 
        SET reentrant_count = reentrant_count - 1 
        WHERE lock_name = p_lock_name AND lock_value = p_lock_value;
        SET p_result = 2;
    ELSE
        -- 完全释放
        DELETE FROM distributed_lock 
        WHERE lock_name = p_lock_name AND lock_value = p_lock_value;
        SET p_result = 1;
    END IF;
    
END //

DELIMITER ;


-- ============================================================
-- 存储过程：清理过期锁
-- ============================================================
DELIMITER //

CREATE PROCEDURE sp_clean_expired_locks(
    OUT p_cleaned_count INT
)
BEGIN
    DELETE FROM distributed_lock WHERE expire_time < NOW();
    SET p_cleaned_count = ROW_COUNT();
END //

DELIMITER ;


-- ============================================================
-- 常用查询
-- ============================================================

-- 查看所有锁
-- SELECT * FROM distributed_lock ORDER BY create_time DESC;

-- 查看过期的锁
-- SELECT * FROM distributed_lock WHERE expire_time < NOW();

-- 查看特定锁
-- SELECT * FROM distributed_lock WHERE lock_name = 'your_lock_name';

-- 手动清理过期锁
-- DELETE FROM distributed_lock WHERE expire_time < NOW();

-- 强制删除特定锁（谨慎使用）
-- DELETE FROM distributed_lock WHERE lock_name = 'your_lock_name';
