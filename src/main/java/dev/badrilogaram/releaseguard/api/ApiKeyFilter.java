package dev.badrilogaram.releaseguard.api;

import dev.badrilogaram.releaseguard.config.GuardProperties;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class ApiKeyFilter extends OncePerRequestFilter {
    private final byte[] expected;
    public ApiKeyFilter(GuardProperties properties) { expected = properties.apiKey().getBytes(StandardCharsets.UTF_8); }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getServletPath();
        boolean protectedPath = path.startsWith("/api/")
                || (path.startsWith("/actuator/") && !path.equals("/actuator/health") && !path.startsWith("/actuator/health/"))
                || (path.startsWith("/demo/") && !request.getMethod().equals("GET"));
        return !protectedPath;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String supplied = request.getHeader("X-API-Key");
        if (supplied == null || !MessageDigest.isEqual(expected, supplied.getBytes(StandardCharsets.UTF_8))) {
            response.setStatus(401);
            response.setContentType("application/problem+json");
            response.getWriter().write("{\"status\":401,\"title\":\"Unauthorized\",\"detail\":\"A valid X-API-Key header is required\"}");
            return;
        }
        chain.doFilter(request, response);
    }
}
