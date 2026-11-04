package cn.crabc.core.app.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.core.json.JsonFactoryBuilder;
import tools.jackson.core.util.JsonRecyclerPools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/**
 * Jackson 回收池配置测试。
 * <p>
 * 该配置此前写在 {@code spring.jackson.core.recycler-pool}（Boot 4.1 并不识别，属于静默失效），
 * 这里锁住「配置真的作用到 JsonFactoryBuilder」这件事。
 *
 * @author yuqf
 */
class JacksonConfigTest {

    @Test
    @DisplayName("shared：默认使用跨线程复用的回收池")
    void sharedPool() {
        JsonFactoryBuilder builder = customize("shared");
        assertInstanceOf(JsonRecyclerPools.ConcurrentDequePool.class, builder.recyclerPool(),
                "shared 策略必须落到跨线程复用池，否则虚拟线程下会反复重建缓冲");
    }

    @Test
    @DisplayName("thread-local：可切回线程本地池用于压测对比")
    void threadLocalPool() {
        JsonFactoryBuilder builder = customize("thread-local");
        assertInstanceOf(JsonRecyclerPools.ThreadLocalPool.class, builder.recyclerPool());
    }

    @Test
    @DisplayName("未知取值回落到 shared，不因配置拼错导致启动失败")
    void unknownFallsBackToShared() {
        JsonFactoryBuilder builder = customize("typo-value");
        assertEquals(JsonRecyclerPools.sharedConcurrentDequePool().getClass(), builder.recyclerPool().getClass());
    }

    private JsonFactoryBuilder customize(String mode) {
        JacksonConfig config = new JacksonConfig();
        ReflectionTestUtils.setField(config, "recyclerPool", mode);
        JsonFactoryBuilder builder = JsonFactory.builder();
        config.recyclerPoolCustomizer().customize(builder);
        return builder;
    }
}
