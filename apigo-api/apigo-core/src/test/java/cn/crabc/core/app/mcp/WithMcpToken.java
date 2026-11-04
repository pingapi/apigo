package cn.crabc.core.app.mcp;

import cn.crabc.core.app.mcp.auth.McpTokenScope;
import cn.crabc.core.app.mcp.auth.McpTokenScopeExtension;
import org.junit.jupiter.api.extension.ExtendWith;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 为整个测试方法（含 @BeforeEach/@AfterEach）绑定 MCP 令牌调用上下文。
 * <p>
 * 替代旧的 {@code McpTestFixture.bindXxxContext()/clearContext()} 成对调用：
 * ScopedValue 作用域只能围绕同步执行体展开，扩展在测试体外包一层绑定。
 * 可标注在测试类（对全部方法生效）或单个方法（覆盖类级默认值）。
 *
 * @author yuqf
 */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@ExtendWith(McpTokenScopeExtension.class)
public @interface WithMcpToken {

    /**
     * 绑定的令牌作用域，默认读写。
     */
    McpTokenScope value() default McpTokenScope.READ_WRITE;
}
