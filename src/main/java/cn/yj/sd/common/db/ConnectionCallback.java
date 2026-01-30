package cn.yj.sd.common.db;

import java.sql.Connection;
import java.sql.SQLException;

/**
 * 数据库连接回调接口
 * 用于在获取连接后执行自定义操作
 *
 * @param <T> 返回类型
 * @author yaojun
 */
@FunctionalInterface
public interface ConnectionCallback<T> {

    /**
     * 使用连接执行操作
     *
     * @param connection 数据库连接
     * @return 操作结果
     * @throws SQLException SQL异常
     */
    T doInConnection(Connection connection) throws SQLException;
}
