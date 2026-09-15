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
     * 与 application.yml 中 crabc.mcp.user-id 默认值一致。
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
        McpToolSupport support = new McpToolSupport(new McpAuditRecorder());
        ReflectionTestUtils.setField(support, "defaultUserId", USER_ID);
        ReflectionTestUtils.setField(support, "maxPageSize", 20);
        ReflectionTestUtils.setField(support, "maxTextLength", 2000);
        return support;
    }

    public static JsonMapper jsonMapper() {
        return new JsonMapper();
    }

    /**
     * 模拟一个读写令牌的调用上下文（变更类工具测试必须先绑定，否则会被 fail-closed 拒绝）。
     */
    public static void bindReadWriteContext() {
        McpAuthContext.set(TOKEN_NAME, McpTokenScope.READ_WRITE, CLIENT_IP);
    }

    /**
     * 模拟一个只读令牌的调用上下文。
     */
    public static void bindReadOnlyContext() {
        McpAuthContext.set(TOKEN_NAME, McpTokenScope.READ_ONLY, CLIENT_IP);
    }

    public static void clearContext() {
        McpAuthContext.clear();
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
