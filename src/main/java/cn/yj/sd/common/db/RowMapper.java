package cn.yj.sd.common.db;

import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * 结果集行映射接口
 * 用于将ResultSet的一行数据映射为Java对象
 *
 * @param <T> 映射的目标类型
 * @author yaojun
 */
@FunctionalInterface
public interface RowMapper<T> {

    /**
     * 将结果集的当前行映射为对象
     *
     * @param rs     结果集（已定位到当前行）
     * @param rowNum 当前行号（从0开始）
     * @return 映射后的对象
     * @throws SQLException SQL异常
     */
    T mapRow(ResultSet rs, int rowNum) throws SQLException;
}
