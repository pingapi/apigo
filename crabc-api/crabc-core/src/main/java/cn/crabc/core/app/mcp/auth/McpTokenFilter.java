package cn.crabc.core.app.mcp.auth;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * MCP 端点鉴权过滤器。
 * <p>
 * 仅作用于 MCP 端点（默认 {@code /mcp}），校验 {@code Authorization: Bearer <token>}。
 * 校验不通过直接返回 401，请求不会进入 MCP 传输层。
 * <p>
 * 采用 Servlet Filter 而非 HandlerInterceptor：MCP 端点由 Spring AI 的
 * {@code RouterFunction} 注册，Filter 不依赖 Handler 类型，覆盖 GET/POST/DELETE 全部方法。
 * <p>
 * 安全说明：
 * <ul>
 *     <li>令牌比较基于 SHA-256 摘要并使用 {@link MessageDigest#isEqual}，避免逐字节短路比较带来的时序差异；</li>
 *     <li>只接受 {@code Authorization: Bearer} 与 {@code X-Mcp-Token} 两种形式，不接受裸凭据；</li>
 *     <li>关闭开关时通过 {@link #denyAll()} 拒绝全部请求（fail-closed），而不是不注册过滤器。</li>
 * </ul>
 *
 * @author yuqf
 */
public class McpTokenFilter implements Filter {

    private static final Logger log = LoggerFactory.getLogger(McpTokenFilter.class);

    /**
     * 401 响应体，保持与控制面一致的 Result 结构（code/msg）。
     */
    private static final String UNAUTHORIZED_BODY = "{\"code\":402,\"msg\":\"用户未登录\"}";

    /**
     * 403 响应体：MCP 功能被显式关闭。
     */
    private static final String DISABLED_BODY = "{\"code\":44000,\"msg\":\"MCP 功能已关闭\"}";

    private static final String BEARER_PREFIX = "bearer ";

    private static final String SHA_256 = "SHA-256";

    /**
     * token 明文 -> 身份定义（用于解析配置与单元测试断言）。
     */
    private final Map<String, McpToken> tokenIndex;

    /**
     * token 摘要 -> 身份定义，用于恒定时间比较。
     */
    private final List<DigestEntry> digestIndex;

    /**
     * true 表示拒绝全部请求（MCP 已关闭或未配置令牌）。
     */
    private final boolean denyAll;

    public McpTokenFilter(Map<String, McpToken> tokenIndex) {
        this(tokenIndex, false);
    }

    private McpTokenFilter(Map<String, McpToken> tokenIndex, boolean denyAll) {
        this.tokenIndex = tokenIndex == null ? Collections.emptyMap() : Collections.unmodifiableMap(tokenIndex);
        this.denyAll = denyAll;
        List<DigestEntry> digests = new ArrayList<>(this.tokenIndex.size());
        for (Map.Entry<String, McpToken> entry : this.tokenIndex.entrySet()) {
            digests.add(new DigestEntry(sha256(entry.getKey()), entry.getValue()));
        }
        this.digestIndex = List.copyOf(digests);
    }

    /**
     * 构造一个拒绝全部请求的过滤器（MCP 关闭 / 未配置令牌时使用）。
     * <p>
     * 必须显式注册该过滤器而不是"不注册"：MCP 端点由 Spring AI 独立暴露，
     * 一旦跳过鉴权过滤器，端点将变为匿名可访问。
     */
    public static McpTokenFilter denyAll() {
        return new McpTokenFilter(Collections.emptyMap(), true);
    }

    /**
     * 已配置的令牌数量。
     */
    public int tokenCount() {
        return tokenIndex.size();
    }

    /**
     * 只读令牌数量（用于启动日志提示）。
     */
    public long readOnlyTokenCount() {
        return tokenIndex.values().stream().filter(token -> !token.writable()).count();
    }

    /**
     * 解析逗号分隔的 token 配置。
     * <p>
     * 单项格式：{@code token} 或 {@code token|名称} 或 {@code token|名称|作用域}。
     * 作用域取 {@code readonly} / {@code readwrite}（默认 readwrite）。
     */
    public static Map<String, McpToken> parseTokens(String rawTokens) {
        Map<String, McpToken> result = new LinkedHashMap<>();
        if (rawTokens == null || rawTokens.isBlank()) {
            return result;
        }
        int index = 0;
        for (String item : rawTokens.split(",")) {
            String raw = item.trim();
            if (raw.isEmpty()) {
                continue;
            }
            index++;
            String[] parts = raw.split("\\|", -1);
            String token = parts[0].trim();
            String name = parts.length > 1 && !parts[1].isBlank() ? parts[1].trim() : "token-" + index;
            McpTokenScope scope = parts.length > 2 ? McpTokenScope.parse(parts[2]) : McpTokenScope.READ_WRITE;
            if (!token.isEmpty()) {
                result.put(token, new McpToken(name, scope));
            }
        }
        return result;
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        HttpServletRequest httpRequest = (HttpServletRequest) request;
        HttpServletResponse httpResponse = (HttpServletResponse) response;

        if (denyAll) {
            writeJson(httpResponse, HttpServletResponse.SC_FORBIDDEN, DISABLED_BODY);
            return;
        }

        // 跨域预检放行（CORS 已由 WebConfiguration 对 /** 统一配置）
        if (HttpMethod.OPTIONS.equalsIgnoreCase(httpRequest.getMethod())) {
            chain.doFilter(request, response);
            return;
        }

        String token = resolveToken(httpRequest);
        McpToken identity = token == null ? null : match(token);
        if (identity == null) {
            log.warn("MCP 鉴权失败 - 来源: {}, 路径: {}", request.getRemoteAddr(), httpRequest.getRequestURI());
            writeJson(httpResponse, HttpServletResponse.SC_UNAUTHORIZED, UNAUTHORIZED_BODY);
            return;
        }

        McpAuthContext.set(identity.name(), identity.scope(), request.getRemoteAddr());
        try {
            chain.doFilter(request, response);
        } finally {
            McpAuthContext.clear();
        }
    }

    /**
     * 恒定时间匹配令牌：先做摘要，再逐一用 {@link MessageDigest#isEqual} 比较。
     */
    private McpToken match(String presented) {
        byte[] digest = sha256(presented);
        McpToken matched = null;
        // 不提前返回，保证比较次数与配置数量一致，避免时序差异
        for (DigestEntry entry : digestIndex) {
            if (MessageDigest.isEqual(entry.digest(), digest)) {
                matched = entry.token();
            }
        }
        return matched;
    }

    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance(SHA_256).digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("当前 JVM 不支持 " + SHA_256, e);
        }
    }

    /**
     * 解析 token：仅接受 {@code Authorization: Bearer <token>} 与 {@code X-Mcp-Token}。
     * <p>
     * 不再接受裸凭据（{@code Authorization: <token>}），避免把其它体系的凭据误当作 MCP 令牌。
     */
    private String resolveToken(HttpServletRequest request) {
        String authorization = getHeaderIgnoreCase(request, "Authorization");
        if (authorization != null && !authorization.isBlank()) {
            String value = authorization.trim();
            if (value.toLowerCase(Locale.ROOT).startsWith(BEARER_PREFIX)) {
                String token = value.substring(BEARER_PREFIX.length()).trim();
                return token.isEmpty() ? null : token;
            }
        }
        String custom = getHeaderIgnoreCase(request, "X-Mcp-Token");
        return custom == null || custom.isBlank() ? null : custom.trim();
    }

    private String getHeaderIgnoreCase(HttpServletRequest request, String name) {
        String value = request.getHeader(name);
        if (value != null) {
            return value;
        }
        // 兜底：遍历 header 名做忽略大小写匹配
        Enumeration<String> names = request.getHeaderNames();
        if (names == null) {
            return null;
        }
        while (names.hasMoreElements()) {
            String headerName = names.nextElement();
            if (headerName.equalsIgnoreCase(name)) {
                return request.getHeader(headerName);
            }
        }
        return null;
    }

    private void writeJson(HttpServletResponse response, int status, String body) throws IOException {
        response.setStatus(status);
        if (status == HttpServletResponse.SC_UNAUTHORIZED) {
            response.setHeader("WWW-Authenticate", "Bearer realm=\"apigo-mcp\"");
        }
        response.setContentType("application/json;charset=UTF-8");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(body);
    }

    /**
     * 仅使用到的 HTTP 方法常量，避免额外依赖。
     */
    private static final class HttpMethod {
        private static final String OPTIONS = "OPTIONS";

        private HttpMethod() {
        }
    }

    /**
     * 令牌摘要与身份的绑定项。
     */
    private record DigestEntry(byte[] digest, McpToken token) {
    }
}
