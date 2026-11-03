package rw.rra.roomiq.audit.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import rw.rra.roomiq.audit.integration.AuditSearchAuthorizationDecision;
import rw.rra.roomiq.audit.integration.IdentityAuditSearchAuthorizationClient;
import rw.rra.roomiq.common.web.ApiError;
import rw.rra.roomiq.common.web.CorrelationIdFilter;
import rw.rra.roomiq.common.web.DomainException;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Component
public class AuditIdentityAuthorizationFilter extends OncePerRequestFilter {
    public static final String AUTHORIZATION_ATTRIBUTE =
            AuditIdentityAuthorizationFilter.class.getName() + ".decision";

    private final IdentityAuditSearchAuthorizationClient identityClient;
    private final ObjectMapper objectMapper;

    public AuditIdentityAuthorizationFilter(IdentityAuditSearchAuthorizationClient identityClient,
                                            ObjectMapper objectMapper) {
        this.identityClient = identityClient;
        this.objectMapper = objectMapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String contextPath = request.getContextPath();
        String requestPath = request.getRequestURI().substring(contextPath.length());
        return !requestPath.equals("/api") && !requestPath.startsWith("/api/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        if (!(org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication()
                instanceof JwtAuthenticationToken authentication)) {
            writeError(request, response, HttpStatus.UNAUTHORIZED, "AUTHENTICATION_REQUIRED",
                    "Authentication is required");
            return;
        }

        UUID authenticatedUserId;
        try {
            authenticatedUserId = UUID.fromString(authentication.getToken().getSubject());
        } catch (RuntimeException exception) {
            writeError(request, response, HttpStatus.UNAUTHORIZED, "AUTHENTICATION_REQUIRED",
                    "Authentication is required");
            return;
        }

        AuditSearchAuthorizationDecision decision;
        try {
            decision = identityClient.authorize(request.getHeader("Authorization"), authenticatedUserId,
                    CorrelationIdFilter.resolveCorrelationId(request));
        } catch (DomainException exception) {
            writeError(request, response, exception.status(), exception.code(), exception.getMessage());
            return;
        }
        request.setAttribute(AUTHORIZATION_ATTRIBUTE, decision);
        filterChain.doFilter(request, response);
    }

    private void writeError(HttpServletRequest request, HttpServletResponse response, HttpStatus status,
                            String code, String message) throws IOException {
        String correlationId = CorrelationIdFilter.resolveCorrelationId(request);
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setHeader(CorrelationIdFilter.HEADER_NAME, correlationId);
        objectMapper.writeValue(response.getOutputStream(), new ApiError(code, message,
                request.getRequestURI(), status.value(), correlationId, List.of(), Instant.now()));
    }
}
