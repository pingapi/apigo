package cn.crabc.core.app.mcp.auth;

/**
 * MCP 调用身份上下文。
 * <p>
 * 由 {@link McpTokenFilter} 在请求鉴权通过后写入，工具层读取后用于两件事：
 * 绑定业务归属用户（{@code UserThreadLocal}）与校验令牌作用域。
 * <p>
 * 由于 Streamable-HTTP 存在异步分派的可能，该上下文仅作为「请求级」传递载体；
 * 一旦取不到，作用域按 {@link McpTokenScope#UNKNOWN} 处理（fail-closed）。
 *
 * @author yuqf
 */
public final class McpAuthContext {

    private static final String UNKNOWN_TOKEN_NAME = "default";

    private static final ThreadLocal<Entry> HOLDER = new ThreadLocal<>();

    private McpAuthContext() {
    }

    /**
     * 写入当前请求的 MCP 身份。
     *
     * @param tokenName token 的可读标识（多 token 时用于审计区分）
     * @param scope     令牌作用域
     * @param clientIp  调用方 IP（用于审计）
     */
    public static void set(String tokenName, McpTokenScope scope, String clientIp) {
        HOLDER.set(new Entry(tokenName, scope, clientIp));
    }

    /**
     * 当前调用的 token 标识，取不到时返回 "default"。
     */
    public static String tokenName() {
        Entry entry = HOLDER.get();
        return entry == null || entry.tokenName() == null ? UNKNOWN_TOKEN_NAME : entry.tokenName();
    }

    /**
     * 当前调用的令牌作用域，取不到时返回 {@link McpTokenScope#UNKNOWN}（按只读处理）。
     */
    public static McpTokenScope scope() {
        Entry entry = HOLDER.get();
        return entry == null || entry.scope() == null ? McpTokenScope.UNKNOWN : entry.scope();
    }

    /**
     * 当前调用的来源 IP，取不到时返回 "-"。
     */
    public static String clientIp() {
        Entry entry = HOLDER.get();
        return entry == null || entry.clientIp() == null ? "-" : entry.clientIp();
    }

    /**
     * 清理，必须在使用后调用（虚拟线程会被复用）。
     */
    public static void clear() {
        HOLDER.remove();
    }

    public record Entry(String tokenName, McpTokenScope scope, String clientIp) {
    }
}
