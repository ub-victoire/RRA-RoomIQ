package rw.rra.roomiq.booking.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class BookingAuthorizationWebConfiguration implements WebMvcConfigurer {
    private final BookingAuthorizationInterceptor authorizationInterceptor;
    private final BookingSourceHoldAuthorizationInterceptor holdAuthorizationInterceptor;
    private final PublicShareLinkRateLimitInterceptor rateLimitInterceptor;

    public BookingAuthorizationWebConfiguration(BookingAuthorizationInterceptor authorizationInterceptor,
                                                BookingSourceHoldAuthorizationInterceptor holdAuthorizationInterceptor,
                                                PublicShareLinkRateLimitInterceptor rateLimitInterceptor) {
        this.authorizationInterceptor = authorizationInterceptor;
        this.holdAuthorizationInterceptor = holdAuthorizationInterceptor;
        this.rateLimitInterceptor = rateLimitInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(authorizationInterceptor).addPathPatterns("/api/v1/**");
        registry.addInterceptor(holdAuthorizationInterceptor)
                .addPathPatterns("/internal/v1/legal-holds/instructions");
        registry.addInterceptor(rateLimitInterceptor).addPathPatterns("/api/v1/public/meetings/*");
    }
}
