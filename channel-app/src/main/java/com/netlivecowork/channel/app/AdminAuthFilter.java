package com.netlivecowork.channel.app;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 管理端鉴权：所有 /api/v1/channel/** 需要 Bearer 管理密钥；密钥不交给平台用户，也不进日志
 * （INV-52）。未配置管理密钥时拒绝而不是放行。
 */
final class AdminAuthFilter extends OncePerRequestFilter {

    static final String PREFIX = "/api/v1/channel/";
    private final byte[] token;

    AdminAuthFilter(String adminToken) {
        this.token = adminToken == null ? null : adminToken.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith(PREFIX);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
        throws ServletException, IOException {
        if (token == null || token.length == 0) {
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "admin token is not configured");
            return;
        }
        String header = request.getHeader("Authorization");
        String presented = header != null && header.startsWith("Bearer ") ? header.substring(7) : null;
        if (presented == null
            || !MessageDigest.isEqual(token, presented.getBytes(StandardCharsets.UTF_8))) {
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "invalid admin credential");
            return;
        }
        chain.doFilter(request, response);
    }
}
