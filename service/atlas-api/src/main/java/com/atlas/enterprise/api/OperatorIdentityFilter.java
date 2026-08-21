package com.atlas.enterprise.api;

import jakarta.annotation.PostConstruct;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Establishes the operator identity at the service boundary.
 *
 * <p>LOCAL mode exists only for local development and automated tests. PROXY
 * mode accepts identity only from a trusted gateway that proves possession of
 * a shared secret. The request is wrapped so a browser supplied
 * X-Operator-Id can never override the authenticated identity.</p>
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class OperatorIdentityFilter extends OncePerRequestFilter {
    public static final String OPERATOR_HEADER = "X-Operator-Id";
    public static final String AUTHENTICATED_USER_HEADER =
        "X-Atlas-Authenticated-User";
    public static final String ROLES_HEADER = "X-Atlas-Roles";
    public static final String PROXY_SECRET_HEADER = "X-Atlas-Proxy-Secret";
    public static final String OPERATOR_ATTRIBUTE = "atlas.operatorId";
    public static final String ROLES_ATTRIBUTE = "atlas.roles";

    private final Mode mode;
    private final String proxySecret;

    public OperatorIdentityFilter(
        @Value("${atlas.security.mode:local}") String mode,
        @Value("${atlas.security.proxy-secret:}") String proxySecret
    ) {
        this.mode = Mode.from(mode);
        this.proxySecret = proxySecret == null ? "" : proxySecret;
    }

    @PostConstruct
    void validateConfiguration() {
        if (mode == Mode.PROXY && proxySecret.length() < 32) {
            throw new IllegalStateException(
                "atlas.security.proxy-secret must contain at least 32 characters in PROXY mode"
            );
        }
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return "OPTIONS".equalsIgnoreCase(request.getMethod())
            || "/actuator/health".equals(path)
            || path.startsWith("/actuator/health/")
            || "/actuator/info".equals(path)
            || (!path.startsWith("/api/") && !path.startsWith("/actuator/"));
    }

    @Override
    protected void doFilterInternal(
        HttpServletRequest request,
        HttpServletResponse response,
        FilterChain filterChain
    ) throws ServletException, IOException {
        if (mode == Mode.LOCAL) {
            filterChain.doFilter(request, response);
            return;
        }

        if (!secretMatches(request.getHeader(PROXY_SECRET_HEADER))) {
            reject(response, HttpServletResponse.SC_UNAUTHORIZED,
                "AUTHENTICATION_REQUIRED", "Trusted gateway authentication is required");
            return;
        }
        String operatorId = normalized(request.getHeader(AUTHENTICATED_USER_HEADER));
        if (operatorId == null) {
            reject(response, HttpServletResponse.SC_UNAUTHORIZED,
                "IDENTITY_MISSING", "Authenticated operator identity is missing");
            return;
        }
        Set<String> roles = roles(request.getHeader(ROLES_HEADER));
        if (!roles.contains("OPERATOR") && !roles.contains("ADMIN")) {
            reject(response, HttpServletResponse.SC_FORBIDDEN,
                "ROLE_REQUIRED", "OPERATOR or ADMIN role is required");
            return;
        }
        if (requiresAdmin(request.getRequestURI()) && !roles.contains("ADMIN")) {
            reject(response, HttpServletResponse.SC_FORBIDDEN,
                "ADMIN_REQUIRED", "ADMIN role is required for platform management");
            return;
        }

        request.setAttribute(OPERATOR_ATTRIBUTE, operatorId);
        request.setAttribute(ROLES_ATTRIBUTE, roles);
        filterChain.doFilter(new AuthenticatedRequest(request, operatorId), response);
    }

    private boolean secretMatches(String supplied) {
        if (supplied == null || supplied.isBlank()) {
            return false;
        }
        return MessageDigest.isEqual(
            proxySecret.getBytes(StandardCharsets.UTF_8),
            supplied.getBytes(StandardCharsets.UTF_8)
        );
    }

    private static boolean requiresAdmin(String path) {
        return path.startsWith("/api/platform/")
            || path.startsWith("/actuator/metrics");
    }

    private static Set<String> roles(String value) {
        if (value == null || value.isBlank()) {
            return Set.of();
        }
        Set<String> result = new LinkedHashSet<>();
        for (String item : value.split(",")) {
            String role = normalized(item);
            if (role != null) {
                result.add(role.toUpperCase(Locale.ROOT));
            }
        }
        return Collections.unmodifiableSet(result);
    }

    private static String normalized(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    private static void reject(
        HttpServletResponse response,
        int status,
        String code,
        String message
    ) throws IOException {
        response.setStatus(status);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType("application/json");
        response.getWriter().write(
            "{\"code\":\"" + code + "\",\"message\":\"" + message + "\"}"
        );
    }

    private enum Mode {
        LOCAL,
        PROXY;

        private static Mode from(String value) {
            try {
                return value == null ? LOCAL : valueOf(value.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException exception) {
                throw new IllegalStateException(
                    "atlas.security.mode must be LOCAL or PROXY",
                    exception
                );
            }
        }
    }

    private static final class AuthenticatedRequest extends HttpServletRequestWrapper {
        private final String operatorId;

        private AuthenticatedRequest(HttpServletRequest request, String operatorId) {
            super(request);
            this.operatorId = operatorId;
        }

        @Override
        public String getHeader(String name) {
            return OPERATOR_HEADER.equalsIgnoreCase(name)
                ? operatorId
                : super.getHeader(name);
        }

        @Override
        public Enumeration<String> getHeaders(String name) {
            return OPERATOR_HEADER.equalsIgnoreCase(name)
                ? Collections.enumeration(Set.of(operatorId))
                : super.getHeaders(name);
        }
    }
}
