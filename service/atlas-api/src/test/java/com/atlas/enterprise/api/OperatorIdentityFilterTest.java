package com.atlas.enterprise.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import jakarta.servlet.FilterChain;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class OperatorIdentityFilterTest {
    private static final String SECRET = "0123456789abcdef0123456789abcdef";

    @Test
    void proxyModeRejectsAnonymousBusinessRequest() throws Exception {
        OperatorIdentityFilter filter = new OperatorIdentityFilter("proxy", SECRET);
        filter.validateConfiguration();
        MockHttpServletRequest request = request("/api/tasks");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, unusedChain());

        assertEquals(401, response.getStatus());
        assertFalse(response.getContentAsString().isBlank());
    }

    @Test
    void operatorCannotAccessPlatformAdministration() throws Exception {
        OperatorIdentityFilter filter = new OperatorIdentityFilter("proxy", SECRET);
        filter.validateConfiguration();
        MockHttpServletRequest request = authenticated("/api/platform/risk-rules", "OPERATOR");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, unusedChain());

        assertEquals(403, response.getStatus());
    }

    @Test
    void trustedIdentityOverridesSpoofedOperatorHeader() throws Exception {
        OperatorIdentityFilter filter = new OperatorIdentityFilter("proxy", SECRET);
        filter.validateConfiguration();
        MockHttpServletRequest request = authenticated("/api/tasks", "OPERATOR");
        request.addHeader(OperatorIdentityFilter.OPERATOR_HEADER, "spoofed-user");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<String> observed = new AtomicReference<>();
        FilterChain chain = (filteredRequest, filteredResponse) -> observed.set(
            ((jakarta.servlet.http.HttpServletRequest) filteredRequest)
                .getHeader(OperatorIdentityFilter.OPERATOR_HEADER)
        );

        filter.doFilter(request, response, chain);

        assertEquals(200, response.getStatus());
        assertEquals("gateway-user", observed.get());
    }

    @Test
    void adminCanAccessPlatformAdministration() throws Exception {
        OperatorIdentityFilter filter = new OperatorIdentityFilter("proxy", SECRET);
        filter.validateConfiguration();
        MockHttpServletRequest request = authenticated(
            "/api/platform/risk-rules", "OPERATOR,ADMIN"
        );
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<Boolean> invoked = new AtomicReference<>(false);

        filter.doFilter(request, response, (ignoredRequest, ignoredResponse) ->
            invoked.set(true));

        assertEquals(200, response.getStatus());
        assertEquals(true, invoked.get());
    }

    private static MockHttpServletRequest authenticated(String path, String roles) {
        MockHttpServletRequest request = request(path);
        request.addHeader(OperatorIdentityFilter.PROXY_SECRET_HEADER, SECRET);
        request.addHeader(
            OperatorIdentityFilter.AUTHENTICATED_USER_HEADER,
            "gateway-user"
        );
        request.addHeader(OperatorIdentityFilter.ROLES_HEADER, roles);
        return request;
    }

    private static MockHttpServletRequest request(String path) {
        return new MockHttpServletRequest("GET", path);
    }

    private static FilterChain unusedChain() {
        return (request, response) -> {
            throw new AssertionError("Filter chain must not be invoked");
        };
    }
}
