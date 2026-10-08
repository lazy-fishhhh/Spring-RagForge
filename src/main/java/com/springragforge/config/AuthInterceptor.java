package com.springragforge.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 轻量鉴权拦截器：/chat /admin /files 必须带非空 X-User-Id 头，否则 401。
 *
 * <p>userId 是登录后前端存的公开值，能被伪造——这里只挡「不登录直接调接口」
 * 的普通访问，不算强安全。要强鉴权得发 token/JWT。
 */
@Component
public class AuthInterceptor implements HandlerInterceptor {

    public static final String HEADER = "X-User-Id";

    @Override
    public boolean preHandle(HttpServletRequest req, HttpServletResponse resp, Object handler) throws Exception {
        String userId = req.getHeader(HEADER);
        if (userId == null || userId.isBlank()) {
            resp.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            resp.setContentType("application/json;charset=UTF-8");
            resp.getWriter().write("{\"success\":false,\"message\":\"未登录\",\"data\":null}");
            return false;
        }
        return true;
    }
}
