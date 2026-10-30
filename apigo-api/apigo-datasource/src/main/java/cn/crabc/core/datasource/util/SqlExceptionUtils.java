package cn.crabc.core.datasource.util;

import java.net.SocketTimeoutException;
import java.sql.SQLException;
import java.sql.SQLTimeoutException;
import java.sql.SQLTransientConnectionException;
import java.util.Locale;
import java.util.Set;

/**
 * SQL异常识别工具：区分脚本执行超时与普通SQL错误。
 *
 * <p>各数据库驱动对 setQueryTimeout 的落地方式不同，超时异常并不统一抛
 * SQLTimeoutException，这里按异常类型、SQLState、错误码和消息做多级识别。
 */
public class SqlExceptionUtils {

    private static final int MAX_CAUSE_DEPTH = 10;

    /**
     * 超时类SQLState：HYT00 通用超时、70100 MySQL中断、57014 PostgreSQL语句取消
     */
    private static final Set<String> TIMEOUT_SQL_STATES = Set.of("HYT00", "70100", "57014");

    /**
     * 超时错误码：MySQL 1317/3024、Oracle 1013、SQLServer -2/258
     */
    private static final Set<Integer> TIMEOUT_ERROR_CODES = Set.of(1317, 3024, 1013, -2, 258);

    /**
     * 锁等待和死锁不是脚本超时，优先排除（1222/258 为 SQLServer 锁等待错误码）
     */
    private static final Set<Integer> EXCLUDED_ERROR_CODES = Set.of(1205, 1213, 1222);

    /**
     * 包装异常的外层消息可能同时包含锁等待与 timeout 字样，需先按语义排除
     */
    private static final String[] NON_TIMEOUT_MESSAGES = {
        "lock wait timeout",
        "lock request time out",
        "deadlock"
    };

    private static final String[] TIMEOUT_MESSAGES = {
        "timeout",
        "timed out",
        "query execution was interrupted",
        "canceling statement",
        "statement cancelled",
        "ora-01013",
        "超时"
    };

    private SqlExceptionUtils() {
    }

    /**
     * 判断异常链中是否存在脚本执行超时。
     *
     * @param throwable 执行异常
     * @return true 表示超时
     */
    public static boolean isTimeout(Throwable throwable) {
        Throwable current = throwable;
        for (int depth = 0; current != null && depth < MAX_CAUSE_DEPTH; depth++) {
            if (current instanceof SQLTimeoutException || current instanceof SocketTimeoutException) {
                return true;
            }
            // 连接池获取连接超时属于资源问题，不能误判为脚本超时
            if (current instanceof SQLTransientConnectionException) {
                return false;
            }
            if (containsKeyword(current.getMessage(), NON_TIMEOUT_MESSAGES)) {
                return false;
            }
            if (current instanceof SQLException sqlException) {
                Integer errorCode = sqlException.getErrorCode();
                if (errorCode != null && EXCLUDED_ERROR_CODES.contains(errorCode)) {
                    return false;
                }
                String sqlState = sqlException.getSQLState();
                if (sqlState != null && TIMEOUT_SQL_STATES.contains(sqlState.toUpperCase(Locale.ROOT))) {
                    return true;
                }
                if (errorCode != null && TIMEOUT_ERROR_CODES.contains(errorCode)) {
                    return true;
                }
            }
            if (containsKeyword(current.getMessage(), TIMEOUT_MESSAGES)) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private static boolean containsKeyword(String message, String[] keywords) {
        if (message == null) {
            return false;
        }
        String lowerMessage = message.toLowerCase(Locale.ROOT);
        for (String keyword : keywords) {
            if (lowerMessage.contains(keyword)) {
                return true;
            }
        }
        return false;
    }
}
