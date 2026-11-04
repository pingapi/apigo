package cn.crabc.core.app.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.jackson.autoconfigure.JsonFactoryBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.core.util.BufferRecycler;
import tools.jackson.core.util.JsonRecyclerPools;
import tools.jackson.core.util.RecyclerPool;

import java.util.Locale;

/**
 * Jackson 3 缓冲区回收池配置。
 * <p>
 * Spring Boot 4.1 的 {@code spring.jackson.*} 并没有 {@code core.recycler-pool} 子项
 * （JacksonProperties 里只有 read/write/factory/datatype 等），写在该命名空间下会被静默忽略，
 * 因此这里通过 {@link JsonFactoryBuilderCustomizer} 显式设置。
 * <p>
 * 背景：虚拟线程模式下每个请求都是新线程，Jackson 默认的 ThreadLocal 池会随线程反复创建缓冲，
 * 跨线程复用的 shared 池可以显著减少分配；有明确压测依据时可切回 thread-local 对比。
 *
 * @author yuqf
 */
@Configuration
public class JacksonConfig {

    /**
     * 有界池容量：按并发量预估，容量过小会退化为频繁新建缓冲。
     */
    private static final int BOUNDED_POOL_SIZE = 512;

    /**
     * 回收池策略：shared（默认，跨线程复用）| thread-local | non-recycling | bounded。
     */
    @Value("${crabc.jackson.recycler-pool:shared}")
    private String recyclerPool;

    @Bean
    public JsonFactoryBuilderCustomizer recyclerPoolCustomizer() {
        return builder -> builder.recyclerPool(resolvePool());
    }

    /**
     * 解析配置，未知取值一律回落到 shared（与旧配置语义一致），避免拼错导致启动失败。
     */
    private RecyclerPool<BufferRecycler> resolvePool() {
        String mode = recyclerPool == null ? "shared" : recyclerPool.trim().toLowerCase(Locale.ROOT);
        return switch (mode) {
            case "thread-local" -> JsonRecyclerPools.threadLocalPool();
            case "non-recycling" -> JsonRecyclerPools.nonRecyclingPool();
            case "bounded" -> JsonRecyclerPools.newBoundedPool(BOUNDED_POOL_SIZE);
            default -> JsonRecyclerPools.sharedConcurrentDequePool();
        };
    }
}
