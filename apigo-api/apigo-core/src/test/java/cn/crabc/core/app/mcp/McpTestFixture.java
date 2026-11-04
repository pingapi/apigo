package cn.crabc.core.app.mcp;

import cn.crabc.core.app.mcp.auth.McpAuthContext;
import cn.crabc.core.app.mcp.auth.McpTokenScope;
import cn.crabc.core.app.mcp.support.McpAuditRecorder;
import cn.crabc.core.app.mcp.support.McpToolSupport;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;

/**
 * MCP 单元测试公共夹具。
 *
 * @author yuqf
 */
public final class McpTestFixture {

    /**
     * 测试注入的 MCP 归属用户。
     * <p>
     * 注意 application.yml 中 {@code crabc.mcp.user-id} 默认已改为空（未配置时 fail-closed），
     * 这里只是测试用固定值，不代表线上默认值。
     */
    public static final String USER_ID = "1";

    /**
     * 测试用令牌标识。
     */
    public static final String TOKEN_NAME = "test-token";

    /**
     * 测试用调用方 IP。
     */
    public static final String CLIENT_IP = "127.0.0.1";

    private McpTestFixture() {
    }

    /**
     * 构造配置就绪的 McpToolSupport（@Value 字段由反射注入，避免启动 Spring 容器）。
     */
    public static McpToolSupport support() {
        return support(new McpAuditRecorder());
    }

    /**
     * 使用指定审计记录器构造 support（审计断言用例需要开启审计）。
     */
    public static McpToolSupport support(McpAuditRecorder auditRecorder) {
        McpToolSupport support = new McpToolSupport(auditRecorder);
        ReflectionTestUtils.setField(support, "defaultUserId", USER_ID);
        ReflectionTestUtils.setField(support, "maxPageSize", 20);
        ReflectionTestUtils.setField(support, "maxTextLength", 2000);
        return support;
    }

    /**
     * 构造已开启审计的记录器（@Value 字段由反射注入）。
     */
    public static McpAuditRecorder auditRecorder() {
        McpAuditRecorder recorder = new McpAuditRecorder();
        ReflectionTestUtils.setField(recorder, "auditEnabled", true);
        return recorder;
    }

    public static JsonMapper jsonMapper() {
        return new JsonMapper();
    }

    /**
     * 在读写令牌的调用上下文中执行动作（ScopedValue 阴影绑定，退出自动解绑）。
     */
    public static void withReadWriteContext(Runnable action) {
        McpAuthContext.scope(new McpAuthContext.Entry(TOKEN_NAME, McpTokenScope.READ_WRITE, CLIENT_IP), () -> {
            action.run();
            return null;
        });
    }

    /**
     * 在只读令牌的调用上下文中执行动作。
     */
    public static void withReadOnlyContext(Runnable action) {
        McpAuthContext.scope(new McpAuthContext.Entry(TOKEN_NAME, McpTokenScope.READ_ONLY, CLIENT_IP), () -> {
            action.run();
            return null;
        });
    }

    /**
     * PageInfo 无 setter，用构造器构造。
     */
    public static <T> cn.crabc.core.datasource.util.PageInfo<T> page(List<T> list, long total, int pageNum,
                                                                     int pageSize) {
        cn.crabc.core.datasource.util.PageInfo<T> pageInfo =
                new cn.crabc.core.datasource.util.PageInfo<>(list, pageNum, pageSize);
        pageInfo.setTotal(total);
        return pageInfo;
    }
}
