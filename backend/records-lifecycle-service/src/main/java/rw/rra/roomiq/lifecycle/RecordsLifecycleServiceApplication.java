package rw.rra.roomiq.lifecycle;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.time.Clock;

@SpringBootApplication
@EnableScheduling
public class RecordsLifecycleServiceApplication {
    @Bean
    @ConditionalOnMissingBean(Clock.class)
    Clock lifecycleClock() {
        return Clock.systemUTC();
    }

    public static void main(String[] args) {
        SpringApplication.run(RecordsLifecycleServiceApplication.class, args);
    }
}
