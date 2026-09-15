package cn.crabc.core.app.mcp.auth;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import jakarta.servlet.FilterChain;

import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * MCP 端点鉴权过滤器测试。
 *
 * @author yuqf
 */
class McpTokenFilterTest {

    private static final String TOKEN = "sk-test-123";

    @Test
    @DisplayName("token 解析：逗号分隔，支持 token|名称 与 token|名称|作用域")
    void parseTokens() {
        Map<String, McpToken> tokens = McpTokenFilter.parseTokens(
                "  " + TOKEN + "|workbuddy , sk-2, sk-3|报表|readonly ,, ");

        assertEquals(3, tokens.size());
        assertEquals("workbuddy", tokens.get(TOKEN).name());
        assertEquals(McpTokenScope.READ_WRITE, tokens.get(TOKEN).scope());
        assertEquals("token-2", tokens.get("sk-2").name());
        assertEquals("报表", tokens.get("sk-3").name());
        assertEquals(McpTokenScope.READ_ONLY, tokens.get("sk-3").scope());
    }

    @Test
    @DisplayName("token 解析：作用域写错时按只读处理（fail-closed）")
    void parseTokensUnknownScope() {
        Map<String, McpToken> tokens = McpTokenFilter.parseTokens("sk-x|n|whatever");
        assertEquals(McpTokenScope.READ_ONLY, tokens.get("sk-x").scope());
    }

    @Test
    @DisplayName("token 解析：空配置返回空集合")
    void parseTokensBlank() {
        assertTrue(McpTokenFilter.parseTokens(null).isEmpty());
        assertTrue(McpTokenFilter.parseTokens("  ").isEmpty());
        assertTrue(McpTokenFilter.parseTokens(" , ").isEmpty());
    }

    @Test
    @DisplayName("无 token：401 + WWW-Authenticate，不进入 MCP 层")
    void missingToken() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/mcp");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean passed = new AtomicBoolean(false);
        FilterChain chain = (req, res) -> passed.set(true);

        filter().doFilter(request, response, chain);

        assertEquals(401, response.getStatus());
        assertNotNull(response.getHeader("WWW-Authenticate"));
        assertTrue(response.getContentAsString().contains("用户未登录"));
        assertEquals(false, passed.get());
    }

    @Test
    @DisplayName("错误 token：401")
    void wrongToken() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/mcp");
        request.addHeader("Authorization", "Bearer sk-wrong");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter().doFilter(request, response, (req, res) -> {
        });

        assertEquals(401, response.getStatus());
    }

    @Test
    @DisplayName("正确 token：放行并写入身份与作用域，结束后清理线程上下文")
    void validToken() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/mcp");
        request.addHeader("Authorization", "Bearer " + TOKEN);
        request.setRemoteAddr("10.0.0.9");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<String> tokenNameInChain = new AtomicReference<>();
        AtomicReference<McpTokenScope> scopeInChain = new AtomicReference<>();
        AtomicReference<String> ipInChain = new AtomicReference<>();
        FilterChain chain = (req, res) -> {
            tokenNameInChain.set(McpAuthContext.tokenName());
            scopeInChain.set(McpAuthContext.scope());
            ipInChain.set(McpAuthContext.clientIp());
        };

        new McpTokenFilter(McpTokenFilter.parseTokens(TOKEN + "|worker|readonly")).doFilter(request, response, chain);

        assertEquals("worker", tokenNameInChain.get(), "链路内应能读到 token 标识");
        assertEquals(McpTokenScope.READ_ONLY, scopeInChain.get(), "链路内应能读到令牌作用域");
        assertEquals("10.0.0.9", ipInChain.get(), "链路内应能读到调用方 IP");
        assertEquals("default", McpAuthContext.tokenName(), "请求结束后必须清理，防止线程复用串号");
        assertEquals(McpTokenScope.UNKNOWN, McpAuthContext.scope(), "上下文清理后按未知作用域（只读）处理");
    }

    @Test
    @DisplayName("兼容 X-Mcp-Token 头；不接受裸凭据（Authorization 无 Bearer 前缀）")
    void headerForms() throws Exception {
        AtomicBoolean passed = new AtomicBoolean(false);

        MockHttpServletRequest custom = new MockHttpServletRequest("POST", "/mcp");
        custom.addHeader("x-mcp-token", TOKEN);
        filter().doFilter(custom, new MockHttpServletResponse(), (req, res) -> passed.set(true));
        assertTrue(passed.get(), "X-Mcp-Token 应被接受");

        MockHttpServletRequest bare = new MockHttpServletRequest("POST", "/mcp");
        bare.addHeader("Authorization", TOKEN);
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter().doFilter(bare, response, (req, res) -> passed.set(false));
        assertEquals(401, response.getStatus(), "裸凭据不应被接受，避免把其它体系凭据当作 MCP 令牌");
    }

    @Test
    @DisplayName("OPTIONS 预检放行，不做鉴权")
    void optionsPassThrough() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("OPTIONS", "/mcp");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean passed = new AtomicBoolean(false);

        filter().doFilter(request, response, (req, res) -> passed.set(true));

        assertTrue(passed.get());
        assertEquals(200, response.getStatus());
    }

    @Test
    @DisplayName("token 未配置：拒绝全部请求（fail-closed）")
    void emptyTokenIndex() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/mcp");
        request.addHeader("Authorization", "Bearer " + TOKEN);
        MockHttpServletResponse response = new MockHttpServletResponse();

        new McpTokenFilter(McpTokenFilter.parseTokens("")).doFilter(request, response, (req, res) -> {
        });

        assertEquals(401, response.getStatus());
    }

    @Test
    @DisplayName("MCP 关闭：拒绝全部请求（含预检），而不是放行")
    void denyAll() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/mcp");
        request.addHeader("Authorization", "Bearer " + TOKEN);
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean passed = new AtomicBoolean(false);

        McpTokenFilter.denyAll().doFilter(request, response, (req, res) -> passed.set(true));

        assertEquals(403, response.getStatus());
        assertEquals(false, passed.get(), "关闭状态下不得进入 MCP 层");
        assertTrue(response.getContentAsString().contains("MCP 功能已关闭"));
    }

    @Test
    @DisplayName("只读令牌统计用于启动提示")
    void tokenCount() {
        McpTokenFilter filter = new McpTokenFilter(McpTokenFilter.parseTokens(
                "a|rw , b|ro|readonly , c|ro2|readonly"));
        assertEquals(3, filter.tokenCount());
        assertEquals(2, filter.readOnlyTokenCount());
    }

    private McpTokenFilter filter() {
        return new McpTokenFilter(McpTokenFilter.parseTokens(TOKEN + "|worker"));
    }
}
