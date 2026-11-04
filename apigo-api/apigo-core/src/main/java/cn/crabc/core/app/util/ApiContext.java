package cn.crabc.core.app.util;

import cn.crabc.core.app.entity.dto.ApiInfoDTO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 开放 API 上下文（JDK 25 ScopedValue 实现，替代 {@code ApiThreadLocal}）。
 * <p>
 * {@code ApiFilter} 在请求入口绑定槽位并包裹整个处理链，{@code AuthInterceptor}
 * 解析出命中的 API 配置后写入请求级只读快照；作用域随请求结束自动解绑，
 * 旧的 finally 双重清理不再需要。
 *
 * @author yuqf
 */
public final class ApiContext {

    private static final Logger log = LoggerFactory.getLogger(ApiContext.class);

    /**
     * 请求级槽位：Filter 入口绑定，配置在拦截器中解析后填充。
     */
    private static final class Slot {
        volatile ApiInfoDTO apiInfo;
    }

    private static final ScopedValue<Slot> SLOT = ScopedValue.newInstance();

    private ApiContext() {
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
     * 存入本次请求命中的 API 配置快照（AuthInterceptor 调用）。
     * <p>
     * 快照与缓存中的共享对象隔离，请求时间等可变字段只写在快照上。
     * 未绑定作用域（过滤器未覆盖当前请求）时降级为告警并丢弃写入，
     * 避免把「配置缺失」放大成 500。
     *
     * @param api 本次请求专属的 API 配置副本
     */
    public static void set(ApiInfoDTO api) {
        if (!SLOT.isBound()) {
            log.warn("开放API上下文未绑定作用域，配置写入已忽略（请检查 ApiFilter 注册路径是否覆盖当前请求）");
            return;
        }
        SLOT.get().apiInfo = api;
    }

    /**
     * 当前请求命中的 API 配置，未绑定或未解析时返回 null。
     */
    public static ApiInfoDTO get() {
        return SLOT.isBound() ? SLOT.get().apiInfo : null;
    }

    /**
     * 获取apiId，无上下文时返回 null
     */
    public static Long getApiId() {
        ApiInfoDTO api = get();
        return api == null ? null : api.getApiId();
    }
}
