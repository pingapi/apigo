package cn.crabc.core.app.mcp.support;

import cn.crabc.core.app.mcp.auth.McpAuthContext;
import cn.crabc.core.app.util.UserThreadLocal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * MCP 操作审计挂载点。
 * <p>
 * 当前实现落结构化日志（logback 未配置 MDC，审计字段直接写在消息体内便于 grep）。
 * 待平台操作审计表（base_sys_audit_log）落地后，仅需替换本类实现为落库，
 * 调用方（工具层）无需改动。
 *
 * @author yuqf
 */
@Component
public class McpAuditRecorder {

    private static final Logger auditLog = LoggerFactory.getLogger("MCP-AUDIT");

    private static final int MAX_MESSAGE_LENGTH = 200;

    private static final int MAX_ARGS_LENGTH = 300;

    @Value("${crabc.mcp.audit-enabled:true}")
    private boolean auditEnabled;

    /**
     * 记录一次工具调用。
     *
     * @param toolName    工具名
     * @param argsSummary 入参摘要（仅标识类字段，禁止包含密码等敏感信息）
     * @param success     是否成功
     * @param message     失败原因（成功时可为 null）
     * @param costMs      耗时毫秒
     */
    public void record(String toolName, String argsSummary, boolean success, String message, long costMs) {
        if (!auditEnabled) {
            return;
        }
        auditLog.info("tool={} token={} scope={} userId={} ip={} args={} success={} costMs={} msg={}",
                toolName, McpAuthContext.tokenName(), McpAuthContext.scope(), safeUserId(),
                McpAuthContext.clientIp(), args(argsSummary), success, costMs,
                message == null ? "-" : truncate(message, MAX_MESSAGE_LENGTH));
    }

    private String args(String argsSummary) {
        if (argsSummary == null || argsSummary.isBlank()) {
            return "-";
        }
        return truncate(argsSummary.replace('\n', ' '), MAX_ARGS_LENGTH);
    }

    private String safeUserId() {
        try {
            return UserThreadLocal.getUserId();
        } catch (Exception e) {
            return "unknown";
        }
    }

    private String truncate(String text, int max) {
        return text.length() <= max ? text : text.substring(0, max) + "...";
    }
}
