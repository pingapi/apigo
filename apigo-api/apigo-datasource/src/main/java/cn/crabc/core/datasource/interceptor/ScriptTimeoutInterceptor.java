package cn.crabc.core.datasource.interceptor;

import cn.crabc.core.datasource.constant.BaseConstant;
import org.apache.ibatis.executor.statement.StatementHandler;
import org.apache.ibatis.plugin.Interceptor;
import org.apache.ibatis.plugin.Intercepts;
import org.apache.ibatis.plugin.Invocation;
import org.apache.ibatis.plugin.Signature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Map;

/**
 * 接口级脚本超时拦截器。
 *
 * <p>MyBatis 的 {@code @Options(timeout)} 在启动时就固化在 MappedStatement 上，
 * 这里在 prepare 完成后按当前请求参数覆盖语句超时，实现按接口配置超时时间。
 */
@Component
@Intercepts(@Signature(type = StatementHandler.class, method = "prepare", args = {Connection.class, Integer.class}))
public class ScriptTimeoutInterceptor implements Interceptor {

    private static final Logger log = LoggerFactory.getLogger(ScriptTimeoutInterceptor.class);

    @Override
    public Object intercept(Invocation invocation) throws Throwable {
        Object result = invocation.proceed();
        if (!(result instanceof Statement statement)) {
            return result;
        }
        applyMaxRows(statement);
        Integer timeoutSeconds = resolveTimeoutSeconds(invocation);
        if (timeoutSeconds == null) {
            return result;
        }
        try {
            statement.setQueryTimeout(timeoutSeconds);
        } catch (SQLException | RuntimeException e) {
            // 个别驱动不支持语句超时，降级为原有执行行为，不能影响正常请求
            log.debug("设置SQL语句超时失败，超时时间：{}秒", timeoutSeconds, e);
        }
        // 连接级兜底：setQueryTimeout 不覆盖网络半开/读阻塞场景；
        // 连接池归还时会自动重置 networkTimeout，无需手动还原
        applyNetworkTimeout(statement, timeoutSeconds);
        return result;
    }

    /**
     * 连接级超时兜底，与语句级 {@code setQueryTimeout} 互补。
     *
     * @param statement      已创建的语句
     * @param timeoutSeconds 超时时间（秒）
     */
    private void applyNetworkTimeout(Statement statement, int timeoutSeconds) {
        try {
            Connection connection = statement.getConnection();
            if (connection != null) {
                connection.setNetworkTimeout(null, timeoutSeconds * 1000);
            }
        } catch (SQLException | RuntimeException e) {
            // 驱动不支持时降级，不影响正常请求
            log.debug("设置连接级网络超时失败，超时时间：{}秒", timeoutSeconds, e);
        }
    }

    /**
     * 查询行数硬兜底。
     * <p>
     * 分页判定（{@code JdbcStatement#checkPage}）只要能误判一次，整表结果就会进内存。
     * 这里与分页逻辑完全解耦：无论是否走 PageHelper，驱动最多只返回
     * {@link BaseConstant#MAX_RESULT_LIMIT} + 1 行（多取 1 行便于上游识别"已截断"）。
     * DML 语句会忽略该设置，不影响 update/insert 的返回值。
     */
    private void applyMaxRows(Statement statement) {
        try {
            statement.setMaxRows(BaseConstant.MAX_RESULT_LIMIT + 1);
        } catch (SQLException | RuntimeException e) {
            // 个别驱动不支持 setMaxRows，降级为原有行为，不影响正常请求
            log.debug("设置最大返回行数失败", e);
        }
    }

    private Integer resolveTimeoutSeconds(Invocation invocation) {
        if (!(invocation.getTarget() instanceof StatementHandler statementHandler)) {
            return null;
        }
        Object parameterObject = statementHandler.getParameterHandler().getParameterObject();
        // MyBatis 的 ParamMap 对不存在的 key 调 get 会抛 BindingException，必须先 containsKey
        if (!(parameterObject instanceof Map<?, ?> params) || !params.containsKey(BaseConstant.SCRIPT_TIMEOUT_SECONDS)) {
            return null;
        }
        Object value = params.get(BaseConstant.SCRIPT_TIMEOUT_SECONDS);
        if (value == null) {
            return null;
        }
        try {
            int timeoutSeconds = Integer.parseInt(value.toString());
            if (timeoutSeconds < BaseConstant.MIN_SCRIPT_TIMEOUT_SECONDS) {
                return null;
            }
            return Math.min(timeoutSeconds, BaseConstant.MAX_SCRIPT_TIMEOUT_SECONDS);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
