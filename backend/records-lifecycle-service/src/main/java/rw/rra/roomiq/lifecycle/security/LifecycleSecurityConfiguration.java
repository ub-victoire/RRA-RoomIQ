package rw.rra.roomiq.lifecycle.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.core.annotation.Order;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import tools.jackson.databind.ObjectMapper;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.List;

@Configuration
public class LifecycleSecurityConfiguration {
    @Bean
    public SecretKey lifecycleJwtSecretKey(@Value("${roomiq.auth.jwt-secret:}") String secret) {
        byte[] bytes = secret.getBytes(StandardCharsets.UTF_8);
        if (bytes.length < 32) {
            throw new IllegalStateException("JWT_SECRET must contain at least 32 bytes");
        }
        return new SecretKeySpec(bytes, "HmacSHA256");
    }

    @Bean
    public JwtDecoder lifecycleJwtDecoder(SecretKey lifecycleJwtSecretKey) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(lifecycleJwtSecretKey)
                .macAlgorithm(MacAlgorithm.HS256)
                .build();
        decoder.setJwtValidator(JwtValidators.createDefault());
        return decoder;
    }

    @Bean
    @Order(1)
    public SecurityFilterChain bookingEvidenceSecurityFilterChain(
            HttpSecurity http, BookingSourceEvidenceJwtDecoder bookingSourceEvidenceJwtDecoder) throws Exception {
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(jwt -> {
            Object permissions = jwt.getClaims().get("permissions");
            if (!(permissions instanceof Collection<?> values)) {
                return List.of();
            }
            return values.stream()
                    .filter(BookingSourceEvidenceJwtDecoder.PERMISSION::equals)
                    .map(value -> new SimpleGrantedAuthority("SCOPE_" + value))
                    .toList();
        });
        return http
                .securityMatcher("/internal/v1/source-evidence/booking-requests")
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers(HttpMethod.POST, "/internal/v1/source-evidence/booking-requests")
                        .hasAuthority("SCOPE_" + BookingSourceEvidenceJwtDecoder.PERMISSION)
                        .anyRequest().denyAll())
                .formLogin(form -> form.disable())
                .httpBasic(basic -> basic.disable())
                .logout(logout -> logout.disable())
                .oauth2ResourceServer(oauth2 -> oauth2
                        .authenticationEntryPoint((request, response, exception) ->
                                response.sendError(401, "Booking service authentication is required"))
                        .jwt(jwt -> jwt.decoder(bookingSourceEvidenceJwtDecoder)
                                .jwtAuthenticationConverter(converter)))
                .build();
    }

    @Bean
    @Order(2)
    public SecurityFilterChain lifecycleSecurityFilterChain(
            HttpSecurity http, LifecycleIdentityAuthorizationFilter authorizationFilter,
            ObjectMapper objectMapper) throws Exception {
        return http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers(HttpMethod.GET, "/actuator/health", "/actuator/health/**",
                                "/actuator/info", "/livez", "/readyz").permitAll()
                        .anyRequest().authenticated())
                .formLogin(form -> form.disable())
                .httpBasic(basic -> basic.disable())
                .logout(logout -> logout.disable())
                .oauth2ResourceServer(oauth2 -> oauth2
                        .authenticationEntryPoint((request, response, exception) ->
                                response.sendError(401, "Authentication is required"))
                        .jwt(Customizer.withDefaults()))
                .addFilterAfter(authorizationFilter, BearerTokenAuthenticationFilter.class)
                .build();
    }

    @Bean
    public FilterRegistrationBean<LifecycleIdentityAuthorizationFilter> lifecycleIdentityFilterRegistration(
            LifecycleIdentityAuthorizationFilter filter) {
        FilterRegistrationBean<LifecycleIdentityAuthorizationFilter> registration =
                new FilterRegistrationBean<>(filter);
        registration.setEnabled(false);
        return registration;
    }
}
