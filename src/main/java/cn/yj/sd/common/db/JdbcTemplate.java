package cn.yj.sd.common.db;

import javax.sql.DataSource;
import java.sql.*;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * JDBC操作模板类
 * 简化JDBC操作，提供常用的查询和更新方法
 *
 * @author yaojun
 */
public class JdbcTemplate {

    private final DataSource dataSource;

    /**
     * 使用指定数据源创建JdbcTemplate
     *
     * @param dataSource 数据源
     */
    public JdbcTemplate(DataSource dataSource) {
        if (dataSource == null) {
            throw new IllegalArgumentException("DataSource不能为空");
        }
        this.dataSource = dataSource;
    }

    /**
     * 使用默认数据源创建JdbcTemplate
     */
    public JdbcTemplate() {
        this(MySQLDataSourceManager.getDataSource());
    }

    /**
     * 获取数据源
     *
     * @return DataSource
     */
    public DataSource getDataSource() {
        return dataSource;
    }

    // ==================== 查询方法 ====================

    /**
     * 查询单个对象
     *
     * @param sql       SQL语句
     * @param rowMapper 行映射器
     * @param params    SQL参数
     * @param <T>       返回类型
     * @return 查询结果，不存在返回null
     */
    public <T> T queryForObject(String sql, RowMapper<T> rowMapper, Object... params) {
        List<T> results = query(sql, rowMapper, params);
        if (results.isEmpty()) {
            return null;
        }
        if (results.size() > 1) {
            throw new RuntimeException("查询结果不唯一，返回了 " + results.size() + " 条记录");
        }
        return results.get(0);
    }

    /**
     * 查询单个值（适用于count、max等聚合查询）
     *
     * @param sql        SQL语句
     * @param resultType 返回类型
     * @param params     SQL参数
     * @param <T>        返回类型
     * @return 查询结果
     */
    public <T> T queryForScalar(String sql, Class<T> resultType, Object... params) {
        return queryForObject(sql, (rs, rowNum) -> getColumnValue(rs, 1, resultType), params);
    }

    /**
     * 查询列表
     *
     * @param sql       SQL语句
     * @param rowMapper 行映射器
     * @param params    SQL参数
     * @param <T>       元素类型
     * @return 结果列表
     */
    public <T> List<T> query(String sql, RowMapper<T> rowMapper, Object... params) {
        try (Connection conn = dataSource.getConnection();
                PreparedStatement ps = conn.prepareStatement(sql)) {

            setParameters(ps, params);

            try (ResultSet rs = ps.executeQuery()) {
                List<T> results = new ArrayList<>();
                int rowNum = 0;
                while (rs.next()) {
                    results.add(rowMapper.mapRow(rs, rowNum++));
                }
                return results;
            }
        } catch (SQLException e) {
            throw new RuntimeException("查询失败: " + sql, e);
        }
    }

    /**
     * 查询为Map列表
     *
     * @param sql    SQL语句
     * @param params SQL参数
     * @return Map列表，每个Map代表一行数据
     */
    public List<Map<String, Object>> queryForList(String sql, Object... params) {
        try (Connection conn = dataSource.getConnection();
                PreparedStatement ps = conn.prepareStatement(sql)) {

            setParameters(ps, params);

            try (ResultSet rs = ps.executeQuery()) {
                ResultSetMetaData metaData = rs.getMetaData();
                int columnCount = metaData.getColumnCount();

                List<Map<String, Object>> results = new ArrayList<>();
                while (rs.next()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    for (int i = 1; i <= columnCount; i++) {
                        String columnName = metaData.getColumnLabel(i);
                        if (columnName == null || columnName.isEmpty()) {
                            columnName = metaData.getColumnName(i);
                        }
                        row.put(columnName, rs.getObject(i));
                    }
                    results.add(row);
                }
                return results;
            }
        } catch (SQLException e) {
            throw new RuntimeException("查询失败: " + sql, e);
        }
    }

    /**
     * 查询为单个Map
     *
     * @param sql    SQL语句
     * @param params SQL参数
     * @return 结果Map，不存在返回null
     */
    public Map<String, Object> queryForMap(String sql, Object... params) {
        List<Map<String, Object>> results = queryForList(sql, params);
        if (results.isEmpty()) {
            return null;
        }
        if (results.size() > 1) {
            throw new RuntimeException("查询结果不唯一，返回了 " + results.size() + " 条记录");
        }
        return results.get(0);
    }

    // ==================== 更新方法 ====================

    /**
     * 执行更新操作（INSERT、UPDATE、DELETE）
     *
     * @param sql    SQL语句
     * @param params SQL参数
     * @return 受影响的行数
     */
    public int update(String sql, Object... params) {
        try (Connection conn = dataSource.getConnection();
                PreparedStatement ps = conn.prepareStatement(sql)) {

            setParameters(ps, params);
            return ps.executeUpdate();

        } catch (SQLException e) {
            throw new RuntimeException("更新失败: " + sql, e);
        }
    }

    /**
     * 执行INSERT操作并返回生成的主键
     *
     * @param sql    SQL语句
     * @param params SQL参数
     * @return 生成的主键
     */
    public long insertAndReturnKey(String sql, Object... params) {
        try (Connection conn = dataSource.getConnection();
                PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {

            setParameters(ps, params);
            ps.executeUpdate();

            try (ResultSet rs = ps.getGeneratedKeys()) {
                if (rs.next()) {
                    return rs.getLong(1);
                }
                throw new RuntimeException("未能获取生成的主键");
            }

        } catch (SQLException e) {
            throw new RuntimeException("插入失败: " + sql, e);
        }
    }

    /**
     * 批量更新
     *
     * @param sql         SQL语句
     * @param batchParams 批量参数列表
     * @return 每条SQL影响的行数数组
     */
    public int[] batchUpdate(String sql, List<Object[]> batchParams) {
        try (Connection conn = dataSource.getConnection();
                PreparedStatement ps = conn.prepareStatement(sql)) {

            for (Object[] params : batchParams) {
                setParameters(ps, params);
                ps.addBatch();
            }
            return ps.executeBatch();

        } catch (SQLException e) {
            throw new RuntimeException("批量更新失败: " + sql, e);
        }
    }

    // ==================== 使用指定连接执行（支持事务） ====================

    /**
     * 使用指定连接执行查询
     *
     * @param conn      数据库连接
     * @param sql       SQL语句
     * @param rowMapper 行映射器
     * @param params    SQL参数
     * @param <T>       元素类型
     * @return 结果列表
     */
    public <T> List<T> query(Connection conn, String sql, RowMapper<T> rowMapper, Object... params) {
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            setParameters(ps, params);

            try (ResultSet rs = ps.executeQuery()) {
                List<T> results = new ArrayList<>();
                int rowNum = 0;
                while (rs.next()) {
                    results.add(rowMapper.mapRow(rs, rowNum++));
                }
                return results;
            }
        } catch (SQLException e) {
            throw new RuntimeException("查询失败: " + sql, e);
        }
    }

    /**
     * 使用指定连接执行更新
     *
     * @param conn   数据库连接
     * @param sql    SQL语句
     * @param params SQL参数
     * @return 受影响的行数
     */
    public int update(Connection conn, String sql, Object... params) {
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            setParameters(ps, params);
            return ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("更新失败: " + sql, e);
        }
    }

    /**
     * 使用指定连接执行INSERT并返回主键
     *
     * @param conn   数据库连接
     * @param sql    SQL语句
     * @param params SQL参数
     * @return 生成的主键
     */
    public long insertAndReturnKey(Connection conn, String sql, Object... params) {
        try (PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            setParameters(ps, params);
            ps.executeUpdate();

            try (ResultSet rs = ps.getGeneratedKeys()) {
                if (rs.next()) {
                    return rs.getLong(1);
                }
                throw new RuntimeException("未能获取生成的主键");
            }
        } catch (SQLException e) {
            throw new RuntimeException("插入失败: " + sql, e);
        }
    }

    // ==================== 工具方法 ====================

    /**
     * 设置PreparedStatement参数
     */
    private void setParameters(PreparedStatement ps, Object[] params) throws SQLException {
        if (params != null) {
            for (int i = 0; i < params.length; i++) {
                setParameter(ps, i + 1, params[i]);
            }
        }
    }

    /**
     * 设置单个参数
     */
    private void setParameter(PreparedStatement ps, int index, Object param) throws SQLException {
        if (param == null) {
            ps.setNull(index, Types.NULL);
        } else if (param instanceof String) {
            ps.setString(index, (String) param);
        } else if (param instanceof Integer) {
            ps.setInt(index, (Integer) param);
        } else if (param instanceof Long) {
            ps.setLong(index, (Long) param);
        } else if (param instanceof Double) {
            ps.setDouble(index, (Double) param);
        } else if (param instanceof Float) {
            ps.setFloat(index, (Float) param);
        } else if (param instanceof Boolean) {
            ps.setBoolean(index, (Boolean) param);
        } else if (param instanceof java.util.Date) {
            ps.setTimestamp(index, new Timestamp(((java.util.Date) param).getTime()));
        } else if (param instanceof java.sql.Date) {
            ps.setDate(index, (java.sql.Date) param);
        } else if (param instanceof Timestamp) {
            ps.setTimestamp(index, (Timestamp) param);
        } else if (param instanceof byte[]) {
            ps.setBytes(index, (byte[]) param);
        } else {
            ps.setObject(index, param);
        }
    }

    /**
     * 获取指定类型的列值
     */
    @SuppressWarnings("unchecked")
    private <T> T getColumnValue(ResultSet rs, int index, Class<T> requiredType) throws SQLException {
        Object value;

        if (requiredType == String.class) {
            value = rs.getString(index);
        } else if (requiredType == Integer.class || requiredType == int.class) {
            value = rs.getInt(index);
            if (rs.wasNull())
                value = null;
        } else if (requiredType == Long.class || requiredType == long.class) {
            value = rs.getLong(index);
            if (rs.wasNull())
                value = null;
        } else if (requiredType == Double.class || requiredType == double.class) {
            value = rs.getDouble(index);
            if (rs.wasNull())
                value = null;
        } else if (requiredType == Float.class || requiredType == float.class) {
            value = rs.getFloat(index);
            if (rs.wasNull())
                value = null;
        } else if (requiredType == Boolean.class || requiredType == boolean.class) {
            value = rs.getBoolean(index);
            if (rs.wasNull())
                value = null;
        } else if (requiredType == java.util.Date.class) {
            Timestamp ts = rs.getTimestamp(index);
            value = ts != null ? new java.util.Date(ts.getTime()) : null;
        } else if (requiredType == Timestamp.class) {
            value = rs.getTimestamp(index);
        } else {
            value = rs.getObject(index);
        }

        return (T) value;
    }
}
