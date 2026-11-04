package cn.crabc.core.app.mcp.auth;

/**
 * MCP 调用身份上下文（JDK 25 ScopedValue 实现，替代 ThreadLocal）。
 * <p>
 * 由 {@link McpTokenFilter} 在请求鉴权通过后绑定（鉴权结果在进入链路前已知，可直接绑定），
 * 工具层读取后用于两件事：绑定业务归属用户（{@code UserContext}）与校验令牌作用域。
 * <p>
 * 由于 Streamable-HTTP 存在异步分派的可能，该上下文仅作为「请求级」传递载体；
 * 一旦取不到，作用域按 {@link McpTokenScope#UNKNOWN} 处理（fail-closed）。
 *
 * @author yuqf
 */
public final class McpAuthContext {

    private static final String UNKNOWN_TOKEN_NAME = "default";

    private static final ScopedValue<Entry> HOLDER = ScopedValue.newInstance();

    private McpAuthContext() {
    }

    /**
     * 在当前 MCP 身份的作用域内执行处理链（{@link McpTokenFilter} 调用）。
     * <p>
     * 作用域退出自动解绑，等价于旧的 set/clear 成对操作，但不会遗漏。
     *
     * @param entry 本次请求的 MCP 身份
     * @param action 后续处理体（通常为 chain.doFilter）
     * @param <T> 返回类型
     * @param <X> action 可能抛出的受检异常类型
     * @return action 的返回值
     */
    public static <T, X extends Throwable> T scope(Entry entry, ScopedValue.CallableOp<T, X> action) throws X {
        return ScopedValue.where(HOLDER, entry).call(action);
    }

    /**
     * 当前线程是否处于 MCP 请求作用域内。
     * <p>
     * Streamable-HTTP 在容器内可能异步分派：工具执行线程与 {@link McpTokenFilter}
     * 所在请求线程不一致时本方法返回 false，调用方必须按 fail-closed 处理，
     * 并把该告警作为「传输层上下文未透传」的排障入口，而不是静默降级。
     */
    public static boolean isBound() {
        return HOLDER.isBound();
    }

    /**
     * 当前调用的 token 标识，取不到时返回 "default"。
     */
    public static String tokenName() {
        Entry entry = HOLDER.isBound() ? HOLDER.get() : null;
        return entry == null || entry.tokenName() == null ? UNKNOWN_TOKEN_NAME : entry.tokenName();
    }

    /**
     * 当前调用的令牌作用域，取不到时返回 {@link McpTokenScope#UNKNOWN}（按只读处理）。
     */
    public static McpTokenScope scope() {
        Entry entry = HOLDER.isBound() ? HOLDER.get() : null;
        return entry == null || entry.scope() == null ? McpTokenScope.UNKNOWN : entry.scope();
    }

    /**
     * 当前调用的来源 IP，取不到时返回 "-"。
     */
    public static String clientIp() {
        Entry entry = HOLDER.isBound() ? HOLDER.get() : null;
        return entry == null || entry.clientIp() == null ? "-" : entry.clientIp();
    }

    public record Entry(String tokenName, McpTokenScope scope, String clientIp) {
    }
}
