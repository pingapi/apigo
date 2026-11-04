package cn.crabc.core.app.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 用户上下文作用域测试：覆盖未绑定降级、嵌套绑定、并发隔离与跨线程语义。
 * <p>
 * 这些行为是数据源路由、审计归属与越权判断的前提，必须固化下来：
 * 一旦有人把上下文改回可变全局状态或把执行提交到线程池，本测试应当立即失败。
 * <p>
 * 注意 JDK 25 的继承规则：ScopedValue <b>不会</b>继承给 {@code Thread.start()}
 * 创建的线程（只有 StructuredTaskScope 容器内的线程才继承），因此任何跨线程执行
 * 都必须显式传递身份，不能指望线程上下文。
 *
 * @author yuqf
 */
class UserContextScopeTest {

    private static final UserContext.UserPrincipal ALICE =
            new UserContext.UserPrincipal(1L, "alice", "admin");

    private static final UserContext.UserPrincipal BOB =
            new UserContext.UserPrincipal(2L, "bob", "user");

    @Test
    @DisplayName("未绑定作用域：读取降级为无身份，写入只告警不抛异常")
    void unboundIsSafe() {
        assertNull(UserContext.get());
        assertEquals("0", UserContext.getUserId());
        assertNull(UserContext.getUserName());
        assertFalse(UserContext.isAdmin());
        assertFalse(UserContext.isSuperAdmin());

        assertDoesNotThrow(() -> UserContext.set(ALICE), "作用域外写入不得把可降级场景放大成 500");
        assertNull(UserContext.get(), "作用域外的写入必须被丢弃，不能污染其它线程");
    }

    @Test
    @DisplayName("作用域内写入可见，退出作用域自动解绑")
    void setInsideScope() {
        String userId = UserContext.scope(() -> {
            UserContext.set(ALICE);
            assertTrue(UserContext.isAdmin());
            return UserContext.getUserId();
        });

        assertEquals("1", userId);
        assertNull(UserContext.get(), "作用域退出必须自动解绑，虚拟线程复用不得串号");
    }

    @Test
    @DisplayName("嵌套绑定：内层身份生效，退出后恢复外层身份")
    void nestedBinding() {
        AtomicReference<String> inner = new AtomicReference<>();
        AtomicReference<String> outer = new AtomicReference<>();

        UserContext.scope(() -> {
            UserContext.set(ALICE);
            UserContext.with(BOB, () -> {
                inner.set(UserContext.getUserName());
                return null;
            });
            outer.set(UserContext.getUserName());
            return null;
        });

        assertEquals("bob", inner.get());
        assertEquals("alice", outer.get(), "with 退出后必须恢复外层身份");
    }

    @Test
    @DisplayName("并发隔离：不同线程的绑定互不可见")
    void concurrentIsolation() throws Exception {
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<String>> futures = new ArrayList<>();
            for (int i = 0; i < 64; i++) {
                UserContext.UserPrincipal principal = i % 2 == 0 ? ALICE : BOB;
                futures.add(executor.submit(() -> UserContext.with(principal, UserContext::getUserName)));
            }
            for (int i = 0; i < futures.size(); i++) {
                assertEquals(i % 2 == 0 ? "alice" : "bob", futures.get(i).get(),
                        "并发下身份必须与线程绑定一致");
            }
        }
    }

    @Test
    @DisplayName("跨线程语义：ScopedValue 不继承给 Thread.start 的子线程，预建线程池同样读不到")
    void threadingSemantics() throws Exception {
        AtomicBoolean childRan = new AtomicBoolean(false);
        AtomicReference<String> inherited = new AtomicReference<>("unset");
        UserContext.scope(() -> {
            UserContext.set(ALICE);
            Thread child = Thread.ofVirtual().start(() -> {
                childRan.set(true);
                inherited.set(UserContext.getUserName());
            });
            child.join();
            return null;
        });
        assertTrue(childRan.get(), "子线程必须实际执行，否则断言没有意义");
        assertNull(inherited.get(), "ScopedValue 不继承给 Thread.start 创建的子线程，跨线程必须显式传递身份");

        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            AtomicReference<String> pooled = new AtomicReference<>("unset");
            UserContext.scope(() -> {
                UserContext.set(ALICE);
                Future<String> future = pool.submit(UserContext::getUserName);
                pooled.set(future.get());
                return null;
            });
            assertNull(pooled.get(), "预建线程池的任务不在作用域内，读不到身份：禁止把业务执行提交到线程池");
        } finally {
            pool.shutdownNow();
        }
    }
}
