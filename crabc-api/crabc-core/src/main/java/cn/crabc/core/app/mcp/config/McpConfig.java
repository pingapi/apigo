package cn.crabc.core.app.mcp.config;

import cn.crabc.core.app.mcp.auth.McpToken;
import cn.crabc.core.app.mcp.auth.McpTokenFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

import java.util.Map;

/**
 * MCP 接入配置。
 * <p>
 * 负责把 {@link McpTokenFilter} 注册到 MCP 端点路径上。
 * <p>
 * <b>关键约束</b>：MCP 端点由 Spring AI 自动装配独立暴露，鉴权过滤器是这个端点上
 * 唯一的访问控制。因此过滤器<b>必须无条件注册</b>：
 * <ul>
 *     <li>{@code crabc.mcp.enabled=false} → 注册"拒绝全部"的过滤器（403），真正做到关闭；</li>
 *     <li>{@code crabc.mcp.token} 为空 → 同样拒绝全部请求（fail-closed）。</li>
 * </ul>
 * 若在此处按开关"不注册过滤器"，端点会退化为匿名可访问。
 *
 * @author yuqf
 */
@Configuration
public class McpConfig {

    private static final Logger log = LoggerFactory.getLogger(McpConfig.class);

    @Value("${crabc.mcp.enabled:true}")
    private boolean enabled;

    @Value("${crabc.mcp.token:}")
    private String rawTokens;

    @Value("${spring.ai.mcp.server.streamable-http.mcp-endpoint:/mcp}")
    private String mcpEndpoint;

    @Bean
    public FilterRegistrationBean<McpTokenFilter> mcpTokenFilterRegistration() {
        FilterRegistrationBean<McpTokenFilter> registration = new FilterRegistrationBean<>();
        registration.setName("mcpTokenFilter");
        registration.addUrlPatterns(mcpEndpoint, mcpEndpoint + "/*");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);

        if (!enabled) {
            log.warn("crabc.mcp.enabled=false：MCP 端点 {} 已关闭，全部请求返回 403", mcpEndpoint);
            registration.setFilter(McpTokenFilter.denyAll());
            return registration;
        }

        Map<String, McpToken> tokenIndex = McpTokenFilter.parseTokens(rawTokens);
        McpTokenFilter filter = new McpTokenFilter(tokenIndex);
        if (tokenIndex.isEmpty()) {
            log.error("crabc.mcp.enabled=true 但未配置访问令牌：MCP 端点 {} 将拒绝全部请求（fail-closed）。"
                    + "请通过环境变量 MCP_TOKEN 注入，格式 token|名称|readonly，或显式设置 crabc.mcp.enabled=false", mcpEndpoint);
        } else {
            log.info("MCP Server 已启用：端点={}, 令牌数量={}, 只读令牌数量={}",
                    mcpEndpoint, filter.tokenCount(), filter.readOnlyTokenCount());
        }
        registration.setFilter(filter);
        return registration;
    }
}
