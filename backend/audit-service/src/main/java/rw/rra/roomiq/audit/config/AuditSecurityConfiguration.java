package rw.rra.roomiq.audit.config;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import rw.rra.roomiq.common.web.ApiError;
import rw.rra.roomiq.common.web.CorrelationIdFilter;
import tools.jackson.databind.ObjectMapper;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;

@Configuration
public class AuditSecurityConfiguration {
    @Bean
    public SecretKey auditJwtSecretKey(@Value("${roomiq.auth.jwt-secret:}") String secret) {
        byte[] secretBytes = secret.getBytes(StandardCharsets.UTF_8);
        if (secretBytes.length < 32) {
            throw new IllegalStateException("JWT_SECRET must contain at least 32 bytes");
        }
        return new SecretKeySpec(secretBytes, "HmacSHA256");
    }

    @Bean
    public JwtDecoder auditJwtDecoder(SecretKey auditJwtSecretKey) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(auditJwtSecretKey)
                .macAlgorithm(MacAlgorithm.HS256)
                .build();
        decoder.setJwtValidator(JwtValidators.createDefault());
        return decoder;
    }

    @Bean
    public SecurityFilterChain auditSecurityFilterChain(
            HttpSecurity http, AuditIdentityAuthorizationFilter identityAuthorizationFilter,
            ObjectMapper objectMapper) throws Exception {
        return http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers(HttpMethod.GET, "/actuator/health", "/actuator/info").permitAll()
                        .anyRequest().authenticated())
                .formLogin(form -> form.disable())
                .httpBasic(basic -> basic.disable())
                .logout(logout -> logout.disable())
                .oauth2ResourceServer(oauth2 -> oauth2
                        .authenticationEntryPoint((request, response, exception) ->
                                writeError(objectMapper, request, response, HttpStatus.UNAUTHORIZED,
                                        "AUTHENTICATION_REQUIRED", "Authentication is required"))
                        .jwt(Customizer.withDefaults()))
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint((request, response, exception) ->
                                writeError(objectMapper, request, response, HttpStatus.UNAUTHORIZED,
                                        "AUTHENTICATION_REQUIRED", "Authentication is required"))
                        .accessDeniedHandler((request, response, exception) ->
                                writeError(objectMapper, request, response, HttpStatus.FORBIDDEN,
                                        "ACCESS_DENIED", "Access is denied")))
                .addFilterAfter(identityAuthorizationFilter, BearerTokenAuthenticationFilter.class)
                .build();
    }

    @Bean
    public FilterRegistrationBean<AuditIdentityAuthorizationFilter> auditIdentityFilterRegistration(
            AuditIdentityAuthorizationFilter filter) {
        FilterRegistrationBean<AuditIdentityAuthorizationFilter> registration = new FilterRegistrationBean<>(filter);
        registration.setEnabled(false);
        return registration;
    }

    private static void writeError(ObjectMapper objectMapper, jakarta.servlet.http.HttpServletRequest request,
                                   jakarta.servlet.http.HttpServletResponse response, HttpStatus status,
                                   String code, String message) throws java.io.IOException {
        String correlationId = CorrelationIdFilter.resolveCorrelationId(request);
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setHeader(CorrelationIdFilter.HEADER_NAME, correlationId);
        objectMapper.writeValue(response.getOutputStream(), new ApiError(code, message,
                request.getRequestURI(), status.value(), correlationId, List.of(), Instant.now()));
    }
}
