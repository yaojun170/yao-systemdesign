package cn.yj.sd.common.db;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Collection;
import java.util.stream.Collectors;

/**
 * SQL工具类
 * 提供常用的SQL操作辅助方法
 *
 * @author yaojun
 */
public final class SqlUtils {

    private SqlUtils() {
    }

    /**
     * 安静地关闭Connection
     *
     * @param conn 数据库连接
     */
    public static void closeQuietly(Connection conn) {
        if (conn != null) {
            try {
                if (!conn.isClosed()) {
                    conn.close();
                }
            } catch (SQLException e) {
                // 忽略关闭异常
            }
        }
    }

    /**
     * 安静地关闭Statement
     *
     * @param stmt Statement对象
     */
    public static void closeQuietly(Statement stmt) {
        if (stmt != null) {
            try {
                stmt.close();
            } catch (SQLException e) {
                // 忽略关闭异常
            }
        }
    }

    /**
     * 安静地关闭ResultSet
     *
     * @param rs ResultSet对象
     */
    public static void closeQuietly(ResultSet rs) {
        if (rs != null) {
            try {
                rs.close();
            } catch (SQLException e) {
                // 忽略关闭异常
            }
        }
    }

    /**
     * 安静地关闭所有资源
     *
     * @param conn Connection
     * @param stmt Statement
     * @param rs   ResultSet
     */
    public static void closeQuietly(Connection conn, Statement stmt, ResultSet rs) {
        closeQuietly(rs);
        closeQuietly(stmt);
        closeQuietly(conn);
    }

    /**
     * 安静地回滚事务
     *
     * @param conn 数据库连接
     */
    public static void rollbackQuietly(Connection conn) {
        if (conn != null) {
            try {
                if (!conn.getAutoCommit()) {
                    conn.rollback();
                }
            } catch (SQLException e) {
                // 忽略回滚异常
            }
        }
    }

    /**
     * 生成IN子句的占位符
     * 例如: 传入3，返回 "?, ?, ?"
     *
     * @param count 参数数量
     * @return 占位符字符串
     */
    public static String createInPlaceholders(int count) {
        if (count <= 0) {
            throw new IllegalArgumentException("参数数量必须大于0");
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < count; i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append("?");
        }
        return sb.toString();
    }

    /**
     * 生成IN子句的占位符
     * 例如: 传入集合size为3，返回 "?, ?, ?"
     *
     * @param collection 集合
     * @return 占位符字符串
     */
    public static String createInPlaceholders(Collection<?> collection) {
        if (collection == null || collection.isEmpty()) {
            throw new IllegalArgumentException("集合不能为空");
        }
        return createInPlaceholders(collection.size());
    }

    /**
     * 转义LIKE查询中的特殊字符
     *
     * @param value 原始值
     * @return 转义后的值
     */
    public static String escapeLikePattern(String value) {
        if (value == null) {
            return null;
        }
        return value
                .replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_");
    }

    /**
     * 构建带前缀模糊匹配的LIKE模式
     *
     * @param prefix 前缀
     * @return LIKE模式，如 "abc%"
     */
    public static String likePrefix(String prefix) {
        return escapeLikePattern(prefix) + "%";
    }

    /**
     * 构建带后缀模糊匹配的LIKE模式
     *
     * @param suffix 后缀
     * @return LIKE模式，如 "%abc"
     */
    public static String likeSuffix(String suffix) {
        return "%" + escapeLikePattern(suffix);
    }

    /**
     * 构建全模糊匹配的LIKE模式
     *
     * @param keyword 关键词
     * @return LIKE模式，如 "%abc%"
     */
    public static String likeContains(String keyword) {
        return "%" + escapeLikePattern(keyword) + "%";
    }

    /**
     * 将驼峰命名转为下划线命名
     * 例如: userName -> user_name
     *
     * @param camelCase 驼峰命名
     * @return 下划线命名
     */
    public static String camelToUnderscore(String camelCase) {
        if (camelCase == null || camelCase.isEmpty()) {
            return camelCase;
        }
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < camelCase.length(); i++) {
            char c = camelCase.charAt(i);
            if (Character.isUpperCase(c)) {
                if (i > 0) {
                    result.append('_');
                }
                result.append(Character.toLowerCase(c));
            } else {
                result.append(c);
            }
        }
        return result.toString();
    }

    /**
     * 将下划线命名转为驼峰命名
     * 例如: user_name -> userName
     *
     * @param underscore 下划线命名
     * @return 驼峰命名
     */
    public static String underscoreToCamel(String underscore) {
        if (underscore == null || underscore.isEmpty()) {
            return underscore;
        }
        StringBuilder result = new StringBuilder();
        boolean nextUpper = false;
        for (int i = 0; i < underscore.length(); i++) {
            char c = underscore.charAt(i);
            if (c == '_') {
                nextUpper = true;
            } else {
                if (nextUpper) {
                    result.append(Character.toUpperCase(c));
                    nextUpper = false;
                } else {
                    result.append(c);
                }
            }
        }
        return result.toString();
    }

    /**
     * 构建INSERT语句的VALUES部分
     * 例如: 传入3，返回 "VALUES (?, ?, ?)"
     *
     * @param columnCount 列数
     * @return VALUES子句
     */
    public static String createValuesClause(int columnCount) {
        return "VALUES (" + createInPlaceholders(columnCount) + ")";
    }

    /**
     * 构建批量INSERT语句的VALUES部分
     * 例如: 传入2列、3行，返回 "VALUES (?, ?), (?, ?), (?, ?)"
     *
     * @param columnCount 列数
     * @param rowCount    行数
     * @return VALUES子句
     */
    public static String createBatchValuesClause(int columnCount, int rowCount) {
        if (rowCount <= 0) {
            throw new IllegalArgumentException("行数必须大于0");
        }
        String singleRow = "(" + createInPlaceholders(columnCount) + ")";
        StringBuilder sb = new StringBuilder("VALUES ");
        for (int i = 0; i < rowCount; i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(singleRow);
        }
        return sb.toString();
    }

    /**
     * 构建SET子句
     * 例如: 传入 ["name", "age"]，返回 "SET name = ?, age = ?"
     *
     * @param columns 列名数组
     * @return SET子句
     */
    public static String createSetClause(String... columns) {
        if (columns == null || columns.length == 0) {
            throw new IllegalArgumentException("列名不能为空");
        }
        StringBuilder sb = new StringBuilder("SET ");
        for (int i = 0; i < columns.length; i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(columns[i]).append(" = ?");
        }
        return sb.toString();
    }

    /**
     * 构建ORDER BY子句
     *
     * @param column    列名
     * @param ascending 是否升序
     * @return ORDER BY子句
     */
    public static String createOrderByClause(String column, boolean ascending) {
        return "ORDER BY " + column + (ascending ? " ASC" : " DESC");
    }

    /**
     * 构建LIMIT子句
     *
     * @param offset 偏移量
     * @param limit  限制数量
     * @return LIMIT子句
     */
    public static String createLimitClause(int offset, int limit) {
        return "LIMIT " + offset + ", " + limit;
    }

    /**
     * 构建简单LIMIT子句
     *
     * @param limit 限制数量
     * @return LIMIT子句
     */
    public static String createLimitClause(int limit) {
        return "LIMIT " + limit;
    }
}
