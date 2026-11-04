package cn.crabc.core.app.config;

import cn.crabc.core.app.filter.ApiFilter;
import cn.crabc.core.app.filter.AuthInterceptor;
import cn.crabc.core.app.filter.JwtInterceptor;
import cn.crabc.core.app.filter.UserContextFilter;
import cn.crabc.core.app.mapper.BaseUserMapper;
import cn.crabc.core.app.util.JwtUtil;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;


/**
 * 注册拦截器
 *
 * @author yuqf
 */
@Configuration
public class InterceptorConfig implements WebMvcConfigurer {

    /**
     * 管理端接口前缀。
     * <p>
     * 过滤器使用 servlet 前缀匹配（{@code /api/box/*} 覆盖任意深度子路径），
     * 拦截器使用 MVC 路径匹配（{@code /api/box/**}），两者语义等价，
     * 因此统一由本常量派生，避免两处手工维护后漂移导致上下文缺失。
     */
    private static final String ADMIN_API_PREFIX = "/api/box";

    private static final String ADMIN_API_FILTER_PATTERN = ADMIN_API_PREFIX + "/*";

    private static final String ADMIN_API_PATH_PATTERN = ADMIN_API_PREFIX + "/**";

    /**
     * 无需登录令牌的管理端接口（登录/登出/注册）。
     */
    private static final String[] ADMIN_API_EXCLUDES = {
            ADMIN_API_PREFIX + "/sys/user/login",
            ADMIN_API_PREFIX + "/sys/user/loginout",
            ADMIN_API_PREFIX + "/sys/user/register"
    };

    /**
     * 开放 API 前缀，过滤器与拦截器同样由常量派生。
     */
    private static final String OPEN_API_PREFIX = "/api/web";

    private static final String OPEN_API_FILTER_PATTERN = OPEN_API_PREFIX + "/*";

    private static final String OPEN_API_PATH_PATTERN = OPEN_API_PREFIX + "/**";

    /**
     * 上下文作用域过滤器必须先于其它业务过滤器执行，顺序显式化避免同序号时依赖注册顺序。
     */
    private static final int USER_CONTEXT_FILTER_ORDER = -20;

    private static final int API_FILTER_ORDER = -10;

    private final JwtUtil jwtUtil;

    private final BaseUserMapper baseUserMapper;

    public InterceptorConfig(JwtUtil jwtUtil, BaseUserMapper baseUserMapper) {
        this.jwtUtil = jwtUtil;
        this.baseUserMapper = baseUserMapper;
    }

    @Bean
    public AuthInterceptor apiInterceptor() {
        return new AuthInterceptor();
    }

    @Bean
    public JwtInterceptor jwtInterceptor() {
        // 拦截器需要回查账号状态并取服务端角色，因此依赖 JwtUtil 与用户 Mapper
        return new JwtInterceptor(jwtUtil, baseUserMapper);
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // 登录拦截器
        registry.addInterceptor(jwtInterceptor())
                .addPathPatterns(ADMIN_API_PATH_PATTERN) // 需要拦截的请求
                .excludePathPatterns(ADMIN_API_EXCLUDES); // 不拦截的请求

        // API开放接口拦截器
        registry.addInterceptor(apiInterceptor())
                .addPathPatterns(OPEN_API_PATH_PATTERN); // 需要拦截的请求
    }

    @Value("${crabc.api-log.response-body:false}")
    public Boolean responseBody = false;

    /**
     * 日志过滤器
     * @return
     */
    @Bean
    public FilterRegistrationBean<ApiFilter> builderRegistrationBean() {
        FilterRegistrationBean<ApiFilter> registration = new FilterRegistrationBean<>();
        registration.setFilter(new ApiFilter(responseBody));
        registration.addUrlPatterns(OPEN_API_FILTER_PATTERN);
        registration.setName("apiFilter");
        registration.setOrder(API_FILTER_ORDER);
        return registration;
    }

    /**
     * 用户上下文作用域过滤器：绑定 UserContext（ScopedValue）槽位，
     * 身份由 JwtInterceptor 验签后写入，请求结束自动解绑。
     * @return
     */
    @Bean
    public FilterRegistrationBean<UserContextFilter> userContextFilterRegistration() {
        FilterRegistrationBean<UserContextFilter> registration = new FilterRegistrationBean<>();
        registration.setFilter(new UserContextFilter());
        registration.addUrlPatterns(ADMIN_API_FILTER_PATTERN);
        registration.setName("userContextFilter");
        registration.setOrder(USER_CONTEXT_FILTER_ORDER);
        return registration;
    }
}
