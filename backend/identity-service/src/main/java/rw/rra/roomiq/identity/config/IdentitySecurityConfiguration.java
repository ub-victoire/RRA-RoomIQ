package rw.rra.roomiq.identity.config;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import rw.rra.roomiq.common.web.ApiError;
import rw.rra.roomiq.common.web.CorrelationIdFilter;
import rw.rra.roomiq.identity.domain.service.IdentityAuditService;
import rw.rra.roomiq.identity.domain.repository.UserSessionRepository;
import tools.jackson.databind.ObjectMapper;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Configuration
@EnableMethodSecurity
public class IdentitySecurityConfiguration {
    private static final Logger log = LoggerFactory.getLogger(IdentitySecurityConfiguration.class);

    @Bean
    public SecretKey jwtSecretKey(@Value("${roomiq.auth.jwt-secret:}") String secret) {
        byte[] secretBytes = secret.getBytes(StandardCharsets.UTF_8);
        if (secretBytes.length < 32) {
            throw new IllegalStateException("JWT_SECRET must contain at least 32 bytes");
        }
        return new SecretKeySpec(secretBytes, "HmacSHA256");
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12);
    }

    @Bean
    public JwtEncoder jwtEncoder(SecretKey jwtSecretKey) {
        return new NimbusJwtEncoder(new ImmutableSecret<>(jwtSecretKey));
    }

    @Bean
    public JwtDecoder jwtDecoder(SecretKey jwtSecretKey, UserSessionRepository sessions) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(jwtSecretKey)
                .macAlgorithm(MacAlgorithm.HS256)
                .build();
        OAuth2TokenValidator<Jwt> sessionValidator = jwt -> {
            try {
                UUID sessionId = UUID.fromString(jwt.getId());
                UUID userId = UUID.fromString(jwt.getSubject());
                boolean active = sessions.existsActiveSessionForUser(sessionId, userId, Instant.now());
                return active ? OAuth2TokenValidatorResult.success()
                        : OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token"));
            } catch (RuntimeException exception) {
                return OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token"));
            }
        };
        decoder.setJwtValidator(new org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer("rra-roomiq-identity"), sessionValidator));
        return decoder;
    }

    @Bean
    public SecurityFilterChain identitySecurityFilterChain(HttpSecurity http, ObjectMapper objectMapper,
                                                            IdentityAuditService auditService) throws Exception {
        return http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(authorize -> authorize
                    .requestMatchers(HttpMethod.POST,
                        "/api/v1/auth/login", "/api/v1/auth/refresh", "/api/v1/auth/logout")
                    .permitAll()
                    .requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html",
                        "/actuator/health")
                        .permitAll()
                    .requestMatchers(HttpMethod.POST,
                    "/api/v1/internal/notifications/recipient-resolution",
                    "/api/v1/internal/notifications/delivery-contact")
                    .permitAll()
                    .anyRequest().authenticated())
                .formLogin(form -> form.disable())
                .httpBasic(basic -> basic.disable())
                .logout(logout -> logout.disable())
                .oauth2ResourceServer(oauth2 -> oauth2
                    .authenticationEntryPoint((request, response, exception) -> {
                        recordAuthenticationFailure(auditService);
                        writeSecurityError(objectMapper, request, response, HttpServletResponse.SC_UNAUTHORIZED,
                                "AUTHENTICATION_REQUIRED", "Authentication is required");
                    })
                    .jwt(Customizer.withDefaults()))
                .exceptionHandling(exceptions -> exceptions
                    .authenticationEntryPoint((request, response, exception) -> {
                        recordAuthenticationFailure(auditService);
                        writeSecurityError(objectMapper, request, response, HttpServletResponse.SC_UNAUTHORIZED,
                                "AUTHENTICATION_REQUIRED", "Authentication is required");
                    })
                    .accessDeniedHandler((request, response, exception) -> {
                        recordAuthorizationDenied(auditService);
                        writeSecurityError(objectMapper, request, response, HttpServletResponse.SC_FORBIDDEN,
                                "ACCESS_DENIED", "Access is denied");
                    }))
                .build();
    }

    private void recordAuthenticationFailure(IdentityAuditService auditService) {
        try {
            auditService.recordAuthenticationFailure();
        } catch (RuntimeException exception) {
            log.warn("Authentication failure audit could not be recorded failureType={}",
                    exception.getClass().getSimpleName());
        }
    }

    private void recordAuthorizationDenied(IdentityAuditService auditService) {
        try {
            auditService.recordAuthorizationDenied();
        } catch (RuntimeException exception) {
            log.warn("Authorization denial audit could not be recorded failureType={}",
                    exception.getClass().getSimpleName());
        }
    }

    private void writeSecurityError(ObjectMapper objectMapper, jakarta.servlet.http.HttpServletRequest request,
                                    HttpServletResponse response, int status, String code, String message)
            throws java.io.IOException {
        String correlationId = CorrelationIdFilter.resolveCorrelationId(request);
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setHeader(CorrelationIdFilter.HEADER_NAME, correlationId);
        objectMapper.writeValue(response.getOutputStream(), new ApiError(code, message,
                request.getRequestURI(), status, correlationId, List.of(), Instant.now()));
    }
}