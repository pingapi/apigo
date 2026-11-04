package cn.crabc.core.app.filter;

import cn.crabc.core.app.util.UserContext;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;

import java.io.IOException;

/**
 * 用户上下文作用域过滤器（JDK 25 ScopedValue）。
 * <p>
 * 在请求入口绑定 {@link UserContext} 空槽位并包裹整个处理链：JwtInterceptor
 * 完成验签后写入身份，控制器与业务层在作用域内读取，请求结束自动解绑。
 * 替代旧的 JwtInterceptor afterCompletion 手工 remove，虚拟线程复用下不会串号。
 *
 * @author yuqf
 */
public class UserContextFilter implements Filter {

    @Override
    public void doFilter(ServletRequest servletRequest, ServletResponse servletResponse, FilterChain filterChain)
            throws IOException, ServletException {
        HttpServletRequest request = (HttpServletRequest) servletRequest;
        // 跨域预检不涉及业务身份，直接放行
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            filterChain.doFilter(servletRequest, servletResponse);
            return;
        }
        try {
            UserContext.scope(() -> {
                filterChain.doFilter(servletRequest, servletResponse);
                return null;
            });
        } catch (IOException | ServletException e) {
            throw e;
        } catch (Exception e) {
            // 理论上 scope 不产生其它受检异常，兜底转换以保持容器错误处理语义
            throw new ServletException(e);
        }
    }
}
