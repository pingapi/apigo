package cn.crabc.core.app.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 用户上下文（JDK 25 ScopedValue 实现，替代旧 {@code UserThreadLocal}）。
 * <p>
 * 请求入口由 {@code UserContextFilter} 绑定请求级槽位，{@code JwtInterceptor} 完成验签与
 * 账号回查后写入身份；作用域随请求结束自动解绑，虚拟线程复用下无需手动 remove 防串号。
 * <p>
 * 为什么是「槽位 + 写入」而不是一次性不可变绑定：身份解析（验签 + 回查账户状态）发生在
 * Spring MVC 拦截器阶段，此时已经无法再包裹后续处理链，只能在入口绑定、解析后写入。
 * 因此槽位对外只暴露 {@link UserPrincipal} 快照，写入入口 {@link #set(UserPrincipal)}
 * 只允许鉴权链路调用，且未绑定作用域时降级为告警而不是抛异常。
 * <p>
 * 管理端（/api/box）写入者是 JwtInterceptor，MCP 场景由
 * {@link #with(UserPrincipal, ScopedValue.CallableOp)} 做嵌套绑定，外层身份不受影响。
 *
 * @author yuqf
 */
public final class UserContext {

    private static final Logger log = LoggerFactory.getLogger(UserContext.class);

    /**
     * 用户身份信息（不可变）。
     *
     * @param userId   用户ID，来源为服务端数据库而非令牌声明
     * @param userName 用户账号，MCP 场景为 {@code mcp:<tokenName>}
     * @param role     角色
     */
    public record UserPrincipal(Object userId, String userName, String role) {
    }

    /**
     * 请求级槽位：Filter 入口绑定，身份在链路中途解析后填充。
     * <p>
     * 字段 volatile 保证「解析线程写 -> 同请求子线程读」的可见性；
     * 槽位本身不跨请求复用，作用域退出即不可达。
     */
    private static final class Slot {
        private volatile UserPrincipal principal;
    }

    private static final ScopedValue<Slot> SLOT = ScopedValue.newInstance();

    private UserContext() {
    }

    /**
     * 绑定请求级槽位并执行后续处理链（Filter 入口调用）。
     * <p>
     * 作用域退出自动解绑，等价于旧的 set/remove 成对操作，但不会遗漏。
     *
     * @param action 后续处理体（通常为 chain.doFilter）
     * @param <T>    返回类型
     * @param <X>    action 可能抛出的受检异常类型
     * @return action 的返回值
     */
    public static <T, X extends Throwable> T scope(ScopedValue.CallableOp<T, X> action) throws X {
        return ScopedValue.where(SLOT, new Slot()).call(action);
    }

    /**
     * 嵌套绑定用户身份并执行业务体（MCP 工具场景）。
     * <p>
     * 作用域内读取到的是本次身份，退出后自动恢复外层身份，无需 finally 手工恢复。
     *
     * @param principal 本次工具调用绑定的身份
     * @param action    业务体
     * @param <T>       返回类型
     * @param <X>       action 可能抛出的受检异常类型
     * @return action 的返回值
     */
    public static <T, X extends Throwable> T with(UserPrincipal principal, ScopedValue.CallableOp<T, X> action) throws X {
        Slot slot = new Slot();
        slot.principal = principal;
        return ScopedValue.where(SLOT, slot).call(action);
    }

    /**
     * 身份解析后写入当前请求槽位（仅鉴权链路调用）。
     * <p>
     * 未绑定作用域时降级为「无身份」并告警：旧 ThreadLocal 实现允许任意线程写入，
     * 迁移后若在异步分派、错误分派等作用域外线程调用，抛出 NoSuchElementException 会把
     * 可降级的场景放大成 500，因此这里显式兜底。
     *
     * @param principal 服务端数据库确认的用户身份
     */
    public static void set(UserPrincipal principal) {
        if (!SLOT.isBound()) {
            log.warn("用户上下文未绑定作用域，身份写入已忽略（请检查过滤器注册路径是否覆盖当前请求）");
            return;
        }
        SLOT.get().principal = principal;
    }

    /**
     * 当前用户身份，未绑定或身份未解析时返回 null。
     */
    public static UserPrincipal get() {
        if (!SLOT.isBound()) {
            return null;
        }
        return SLOT.get().principal;
    }

    /**
     * 获取用户ID
     *
     * @return 账号不存在上下文时返回 "0"
     */
    public static String getUserId() {
        Object userId = value("userId");
        return userId == null ? "0" : userId.toString();
    }

    /**
     * 获取用户账号
     *
     * @return 账号，未登录返回 null
     */
    public static String getUserName() {
        Object userName = value("userName");
        return userName == null ? null : userName.toString();
    }

    /**
     * 是否管理员
     * <p>
     * 角色取服务端注入的身份，令牌内的 role 声明不参与判断。
     *
     * @return 是否管理员
     */
    public static boolean isAdmin() {
        Object role = value("role");
        return role != null && "admin".equalsIgnoreCase(role.toString());
    }

    /**
     * 是否超级管理员
     *
     * @return 是否超级管理员
     */
    public static boolean isSuperAdmin() {
        return "1".equals(getUserId()) && isAdmin();
    }

    private static Object value(String key) {
        UserPrincipal principal = get();
        if (principal == null) {
            return null;
        }
        return switch (key) {
            case "userId" -> principal.userId();
            case "userName" -> principal.userName();
            case "role" -> principal.role();
            default -> null;
        };
    }
}
