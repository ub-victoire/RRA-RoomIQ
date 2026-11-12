package rw.rra.roomiq.lifecycle.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import rw.rra.roomiq.common.web.ApiError;
import rw.rra.roomiq.common.web.CorrelationIdFilter;
import rw.rra.roomiq.common.web.DomainException;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Component
public class LifecycleIdentityAuthorizationFilter extends OncePerRequestFilter {
    public static final String ACTOR_ATTRIBUTE = LifecycleIdentityAuthorizationFilter.class.getName() + ".actor";

    private final IdentityRetentionPolicyAuthorizationClient identityClient;
    private final ObjectMapper objectMapper;

    public LifecycleIdentityAuthorizationFilter(IdentityRetentionPolicyAuthorizationClient identityClient,
                                                ObjectMapper objectMapper) {
        this.identityClient = identityClient;
        this.objectMapper = objectMapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        return !path.equals("/api") && !path.startsWith("/api/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        if (!(SecurityContextHolder.getContext().getAuthentication() instanceof JwtAuthenticationToken jwt)) {
            writeError(request, response, HttpStatus.UNAUTHORIZED,
                    "AUTHENTICATION_REQUIRED", "Authentication is required");
            return;
        }

        UUID subject;
        try {
            subject = UUID.fromString(jwt.getToken().getSubject());
        } catch (RuntimeException exception) {
            writeError(request, response, HttpStatus.UNAUTHORIZED,
                    "AUTHENTICATION_REQUIRED", "Authentication is required");
            return;
        }

        UUID trustedActor;
        try {
            String path = request.getRequestURI().substring(request.getContextPath().length());
            if (path.equals("/api/v1/legal-holds") || path.startsWith("/api/v1/legal-holds/")) {
                trustedActor = identityClient.authorizeLegalHolds(request.getHeader("Authorization"), subject,
                        CorrelationIdFilter.resolveCorrelationId(request));
            } else {
                trustedActor = identityClient.authorize(request.getHeader("Authorization"), subject,
                        CorrelationIdFilter.resolveCorrelationId(request));
            }
        } catch (DomainException exception) {
            writeError(request, response, exception.status(), exception.code(), exception.getMessage());
            return;
        }
        request.setAttribute(ACTOR_ATTRIBUTE, trustedActor);
        filterChain.doFilter(request, response);
    }

    private void writeError(HttpServletRequest request, HttpServletResponse response, HttpStatus status,
                            String code, String message) throws IOException {
        String correlationId = CorrelationIdFilter.resolveCorrelationId(request);
        response.setStatus(status.value());
        response.setContentType("application/json");
        response.setHeader(CorrelationIdFilter.HEADER_NAME, correlationId);
        objectMapper.writeValue(response.getOutputStream(), new ApiError(code, message,
                request.getRequestURI(), status.value(), correlationId, List.of(), Instant.now()));
    }
}
