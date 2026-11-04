package cn.crabc.core.app.mcp.auth;

import cn.crabc.core.app.mcp.McpTestFixture;
import cn.crabc.core.app.mcp.WithMcpToken;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.InvocationInterceptor;
import org.junit.jupiter.api.extension.ReflectiveInvocationContext;

import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Method;

/**
 * {@link WithMcpToken} 的实现：在 {@link McpAuthContext}（ScopedValue）作用域内
 * 执行整个测试体，退出自动解绑，无需 @AfterEach 清理。
 *
 * @author yuqf
 */
public class McpTokenScopeExtension implements InvocationInterceptor {

    @Override
    public void interceptTestMethod(Invocation<Void> invocation,
                                    ReflectiveInvocationContext<Method> invocationContext,
                                    ExtensionContext extensionContext) throws Throwable {
        McpTokenScope scope = resolve(extensionContext);
        McpAuthContext.scope(
                new McpAuthContext.Entry(McpTestFixture.TOKEN_NAME, scope, McpTestFixture.CLIENT_IP),
                () -> {
                    invocation.proceed();
                    return null;
                });
    }

    /**
     * 方法级注解优先，其次类级，都未标注时按读写处理。
     */
    private McpTokenScope resolve(ExtensionContext extensionContext) {
        WithMcpToken annotation = annotation(extensionContext.getRequiredTestMethod());
        if (annotation == null) {
            annotation = annotation(extensionContext.getRequiredTestClass());
        }
        return annotation == null ? McpTokenScope.READ_WRITE : annotation.value();
    }

    private WithMcpToken annotation(AnnotatedElement element) {
        return element == null ? null : element.getAnnotation(WithMcpToken.class);
    }
}
