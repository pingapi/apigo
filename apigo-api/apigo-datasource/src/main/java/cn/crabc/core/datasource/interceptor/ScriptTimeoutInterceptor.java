package cn.crabc.core.datasource.interceptor;

import cn.crabc.core.datasource.constant.BaseConstant;
import org.apache.ibatis.executor.statement.StatementHandler;
import org.apache.ibatis.plugin.Interceptor;
import org.apache.ibatis.plugin.Intercepts;
import org.apache.ibatis.plugin.Invocation;
import org.apache.ibatis.plugin.Signature;
import org.apache.ibatis.session.ResultHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Map;
import java.util.concurrent.Executor;

/**
 * 接口级脚本超时拦截器。
 *
 * <p>MyBatis 的 {@code @Options(timeout)} 在启动时就固化在 MappedStatement 上，
 * 这里在 prepare 完成后按当前请求参数覆盖语句超时，实现按接口配置超时时间。
 * <p>
 * 连接级网络超时在语句执行阶段设置、执行结束后立即恢复：连接来自连接池，
 * Druid 归还连接时不会重置 networkTimeout，不恢复会把本次接口的超时残留给后续请求。
 */
@Component
@Intercepts({
        @Signature(type = StatementHandler.class, method = "prepare", args = {Connection.class, Integer.class}),
        @Signature(type = StatementHandler.class, method = "query", args = {Statement.class, ResultHandler.class}),
        @Signature(type = StatementHandler.class, method = "update", args = {Statement.class}),
        @Signature(type = StatementHandler.class, method = "batch", args = {Statement.class})
})
public class ScriptTimeoutInterceptor implements Interceptor {

    private static final Logger log = LoggerFactory.getLogger(ScriptTimeoutInterceptor.class);

    /**
     * 设置网络超时用的执行器。
     * <p>
     * 必须非空：MySQL Connector/J 对 {@code setNetworkTimeout(null, ...)} 直接抛 SQLException，
     * 用 null 会让连接级兜底静默失效。同步执行可保证 socket 超时在语句执行前就已生效。
     */
    private static final Executor NETWORK_TIMEOUT_EXECUTOR = Runnable::run;

    @Override
    public Object intercept(Invocation invocation) throws Throwable {
        if ("prepare".equals(invocation.getMethod().getName())) {
            return interceptPrepare(invocation);
        }
        return interceptExecute(invocation);
    }

    /**
     * prepare 阶段：设置行数上限与语句级超时。
     */
    private Object interceptPrepare(Invocation invocation) throws Throwable {
        Object result = invocation.proceed();
        if (!(result instanceof Statement statement)) {
            return result;
        }
        applyMaxRows(statement);
        Integer timeoutSeconds = resolveTimeoutSeconds(invocation);
        if (timeoutSeconds != null) {
            applyQueryTimeout(statement, timeoutSeconds);
        }
        return result;
    }

    /**
     * 执行阶段：设置连接级网络超时，执行结束后恢复原值。
     */
    private Object interceptExecute(Invocation invocation) throws Throwable {
        Integer timeoutSeconds = resolveTimeoutSeconds(invocation);
        Object[] args = invocation.getArgs();
        if (timeoutSeconds == null || args.length == 0 || !(args[0] instanceof Statement statement)) {
            return invocation.proceed();
        }
        Integer previousTimeout = applyNetworkTimeout(statement, timeoutSeconds);
        try {
            return invocation.proceed();
        } finally {
            restoreNetworkTimeout(statement, previousTimeout);
        }
    }

    /**
     * 语句级超时，驱动不支持时降级为原有执行行为。
     *
     * @param statement      已创建的语句
     * @param timeoutSeconds 超时时间（秒）
     */
    private void applyQueryTimeout(Statement statement, int timeoutSeconds) {
        try {
            statement.setQueryTimeout(timeoutSeconds);
        } catch (SQLException | RuntimeException e) {
            // 个别驱动不支持语句超时，降级为原有执行行为，不能影响正常请求
            log.debug("设置SQL语句超时失败，超时时间：{}秒", timeoutSeconds, e);
        }
    }

    /**
     * 连接级超时兜底，与语句级 {@code setQueryTimeout} 互补。
     *
     * @param statement      已创建的语句
     * @param timeoutSeconds 超时时间（秒）
     * @return 原超时时间（毫秒）；设置失败返回 null，表示无需恢复
     */
    private Integer applyNetworkTimeout(Statement statement, int timeoutSeconds) {
        try {
            Connection connection = statement.getConnection();
            if (connection == null) {
                return null;
            }
            int previousTimeout = connection.getNetworkTimeout();
            connection.setNetworkTimeout(NETWORK_TIMEOUT_EXECUTOR, timeoutSeconds * 1000);
            return previousTimeout;
        } catch (SQLException | RuntimeException e) {
            // 驱动不支持时降级，不影响正常请求
            log.debug("设置连接级网络超时失败，超时时间：{}秒", timeoutSeconds, e);
            return null;
        }
    }

    /**
     * 恢复连接级超时，避免池化连接把本次接口的超时带给后续请求。
     *
     * @param statement       执行完毕的语句
     * @param previousTimeout 原超时时间（毫秒），null 表示无需恢复
     */
    private void restoreNetworkTimeout(Statement statement, Integer previousTimeout) {
        if (previousTimeout == null) {
            return;
        }
        try {
            Connection connection = statement.getConnection();
            if (connection != null) {
                connection.setNetworkTimeout(NETWORK_TIMEOUT_EXECUTOR, previousTimeout);
            }
        } catch (SQLException | RuntimeException e) {
            log.debug("恢复连接级网络超时失败，原超时时间：{}毫秒", previousTimeout, e);
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
