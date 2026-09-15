package cn.crabc.core.app.mcp.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Locale;

/**
 * MCP 令牌作用域。
 * <p>
 * 用于给不同 AI 客户端分配不同权限：只读令牌只能调用查询类工具，
 * 变更类工具（建数据源/建接口/发布/授权等）必须使用 {@link #READ_WRITE} 令牌。
 * <p>
 * 解析失败时按 {@link #READ_ONLY} 处理（fail-closed），避免配置写错导致越权。
 *
 * @author yuqf
 */
public enum McpTokenScope {

    /**
     * 只读：只能调用查询类工具。
     */
    READ_ONLY,

    /**
     * 读写：可调用全部工具。
     */
    READ_WRITE,

    /**
     * 未知：未检测到鉴权上下文（非经 /mcp 端点调用或上下文丢失），一律按只读处理。
     */
    UNKNOWN;

    private static final Logger log = LoggerFactory.getLogger(McpTokenScope.class);

    /**
     * 解析配置中的作用域字符串。
     *
     * @param value 配置值，为空时默认 {@link #READ_WRITE}
     */
    public static McpTokenScope parse(String value) {
        if (value == null || value.isBlank()) {
            return READ_WRITE;
        }
        return switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "readwrite", "read-write", "rw" -> READ_WRITE;
            case "readonly", "read-only", "ro", "read" -> READ_ONLY;
            default -> {
                log.warn("MCP 令牌作用域取值无法识别，已按只读处理：{}（可选 readonly / readwrite）", value);
                yield READ_ONLY;
            }
        };
    }
}
