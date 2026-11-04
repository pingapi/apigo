package cn.crabc.core.app.filter;

import cn.crabc.core.app.entity.BaseUser;
import cn.crabc.core.app.mapper.BaseUserMapper;
import cn.crabc.core.app.util.JwtUtil;
import cn.crabc.core.app.util.UserContext;
import cn.crabc.core.datasource.enums.ErrorStatusEnum;
import cn.crabc.core.datasource.exception.CustomException;
import io.jsonwebtoken.Claims;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.lang.Nullable;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 登录拦截器
 * <p>
 * 令牌验签通过后不做直接信任：按令牌身份回查数据库，确认账号存在且处于启用状态，
 * 并以数据库中的账号与角色作为当前用户身份，令牌内的声明只用于定位用户。
 *
 * @author yuqf
 */
@Slf4j
public class JwtInterceptor implements HandlerInterceptor {

    private final JwtUtil jwtUtil;

    private final BaseUserMapper baseUserMapper;

    public JwtInterceptor(JwtUtil jwtUtil, BaseUserMapper baseUserMapper) {
        this.jwtUtil = jwtUtil;
        this.baseUserMapper = baseUserMapper;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        // 放行OPTIONS请求
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }

        // 获取并校验token
        String token = jwtUtil.getToken(request);
        if (token == null) {
            throw unAuth();
        }

        // 解析token
        Claims claims = jwtUtil.parseToken(token);
        if (claims == null) {
            throw unAuth();
        }

        Long userId = resolveUserId(claims);
        if (userId == null) {
            log.warn("JWT 缺少有效用户标识：sub={}, userId={}", claims.getSubject(), claims.get("userId"));
            throw unAuth();
        }

        // 回查账号：selectOne 带 status='1' 过滤，账号不存在或已停用都查不到，其存量令牌立即失效
        BaseUser user = baseUserMapper.selectOne(userId, null);
        if (user == null) {
            log.warn("JWT 身份校验失败，账号不存在或已停用：userId={}", userId);
            throw unAuth();
        }

        // 设置用户信息到请求上下文（以数据库为准，不信任令牌声明）
        UserContext.set(new UserContext.UserPrincipal(user.getUserId(), user.getUsername(), user.getRole()));
        return true;
    }

    /**
     * 解析用户ID：sub 为权威标识，若同时存在 userId 声明则要求两者一致
     *
     * @param claims 令牌声明
     * @return 用户ID，声明缺失或不一致返回 null
     */
    private Long resolveUserId(Claims claims) {
        String subject = claims.getSubject();
        if (subject == null || subject.isBlank()) {
            return null;
        }
        String id = subject.trim();
        Object claimUserId = claims.get("userId");
        if (claimUserId != null && !id.equals(claimUserId.toString().trim())) {
            log.warn("JWT 内 sub 与 userId 声明不一致：sub={}, userId={}", subject, claimUserId);
            return null;
        }
        try {
            return Long.valueOf(id);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private CustomException unAuth() {
        return new CustomException(ErrorStatusEnum.JWT_LOGIN_EXPIRE.getCode(), ErrorStatusEnum.JWT_UN_AUTH.getMassage());
    }
}
