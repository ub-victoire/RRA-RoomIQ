package rw.rra.roomiq.lifecycle;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class RecordsLifecycleServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(RecordsLifecycleServiceApplication.class, args);
    }
}
