package cn.crabc.core.app.mcp.auth;

/**
 * 一个 MCP 访问令牌的身份定义。
 *
 * @param name  可读标识（用于审计区分调用来源）
 * @param scope 作用域
 * @author yuqf
 */
public record McpToken(String name, McpTokenScope scope) {

    public McpToken {
        if (name == null || name.isBlank()) {
            name = "default";
        }
        if (scope == null) {
            scope = McpTokenScope.READ_WRITE;
        }
    }

    /**
     * 是否允许调用变更类工具。
     */
    public boolean writable() {
        return scope == McpTokenScope.READ_WRITE;
    }
}
