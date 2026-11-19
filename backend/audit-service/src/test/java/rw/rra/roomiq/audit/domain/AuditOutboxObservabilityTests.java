package rw.rra.roomiq.audit.domain;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.Status;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AuditOutboxObservabilityTests {
    @Test
    void databaseFailureMakesOutboxHealthDownWithoutExposingException() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(any(String.class), eq(Long.class), any()))
                .thenThrow(new DataAccessResourceFailureException("private database detail"));
        var observability = new AuditOutboxObservability(jdbc, new SimpleMeterRegistry());

        var health = observability.health();

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails()).containsOnlyKeys("database");
        assertThat(health.getDetails().toString()).doesNotContain("private database detail");
    }
}
