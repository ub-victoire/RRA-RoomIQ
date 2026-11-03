package rw.rra.roomiq.audit;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:audit-app-test;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.flyway.enabled=false",
        "spring.flyway.user=test-migrator",
        "spring.flyway.password=test-migrator-password",
        "spring.jpa.hibernate.ddl-auto=none",
        "roomiq.auth.jwt-secret=test-only-signing-key-at-least-32-bytes-long",
        "roomiq.audit.notification.events.enabled=false",
        "roomiq.audit.identity.events.enabled=false"
})
class AuditServiceApplicationTests {
    @Test
    void contextLoads() {
    }
}
