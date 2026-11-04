package cn.crabc.core.app.filter;

import cn.crabc.core.app.util.ApiContext;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.FilterConfig;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.web.util.ContentCachingResponseWrapper;

import java.io.IOException;

/**
 * 开放 API 过滤器：包装请求/响应体并绑定 {@link ApiContext} 作用域。
 * <p>
 * 整个处理链在 ScopedValue 作用域内执行，AuthInterceptor 解析出 API 配置后写入，
 * 请求结束自动解绑（替代旧的 finally remove 双重清理）。
 */
public class ApiFilter implements Filter {

    public Boolean responseBody;

    public ApiFilter(Boolean responseBody) {
        this.responseBody = responseBody;
    }

    @Override
    public void init(FilterConfig filterConfig) throws ServletException {
        Filter.super.init(filterConfig);
    }

    @Override
    public void doFilter(ServletRequest servletRequest, ServletResponse servletResponse, FilterChain filterChain) throws IOException, ServletException {
        HttpServletRequest request = (HttpServletRequest) servletRequest;
        HttpServletResponse response = (HttpServletResponse) servletResponse;
        try {
            ApiContext.scope(() -> {
                ContentCachingResponseWrapper responseWrapper = null;
                try {
                    // 包装类
                    if (responseBody && (request.getContentType() == null || request.getContentType().contains(MediaType.APPLICATION_JSON_VALUE))) {
                        responseWrapper = new ContentCachingResponseWrapper(response);
                        filterChain.doFilter(new BaseRequestWrapper(request), responseWrapper);
                    } else {
                        filterChain.doFilter(servletRequest, servletResponse);
                    }
                } finally {
                    // 异常路径同样要回写缓冲：否则响应体被整体丢弃，客户端只能拿到空 body
                    if (responseWrapper != null) {
                        responseWrapper.copyBodyToResponse();
                    }
                }
                return null;
            });
        } catch (IOException | ServletException e) {
            throw e;
        } catch (Exception e) {
            // 理论上 scope 不产生其它受检异常，兜底转换以保持容器错误处理语义
            throw new ServletException(e);
        }
    }

    @Override
    public void destroy() {
        Filter.super.destroy();
    }
}
