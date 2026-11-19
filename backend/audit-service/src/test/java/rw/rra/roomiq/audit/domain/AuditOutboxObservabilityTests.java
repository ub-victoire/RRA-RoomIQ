package rw.rra.roomiq.audit.domain;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.Status;
import org.springframework.boot.convert.ApplicationConversionService;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import rw.rra.roomiq.audit.integration.AuditSourceEvidenceProperties;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verifyNoInteractions;

class AuditOutboxObservabilityTests {
    @Test
    void databaseFailureMakesOutboxHealthDownWithoutExposingException() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(any(String.class), eq(Long.class), any()))
                .thenThrow(new DataAccessResourceFailureException("private database detail"));
        var properties = mock(AuditSourceEvidenceProperties.class);
        when(properties.isEnabled()).thenReturn(true);
        var observability = new AuditOutboxObservability(jdbc, new SimpleMeterRegistry(), properties,
                Duration.ofMinutes(15), Duration.ofHours(1));

        var health = observability.health();

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails()).containsOnlyKeys("database");
        assertThat(health.getDetails().toString()).doesNotContain("private database detail");
    }

    @Test
    void disabledDeliveryIsUpWithoutQueryingDatabase() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        var properties = mock(AuditSourceEvidenceProperties.class);
        var observability = new AuditOutboxObservability(jdbc, new SimpleMeterRegistry(), properties,
                Duration.ofMinutes(15), Duration.ofHours(1));
        var health = observability.health();
        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails()).containsEntry("deliveryEnabled", false);
        verifyNoInteractions(jdbc);
    }

    @Test
    void invalidThresholdConfigurationFailsAtStartup() {
        var jdbc = mock(JdbcTemplate.class);
        var properties = mock(AuditSourceEvidenceProperties.class);
        assertThatThrownBy(() -> new AuditOutboxObservability(jdbc, new SimpleMeterRegistry(), properties,
                Duration.ofMinutes(60), Duration.ofMinutes(15))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void configuredWarningThresholdChangesHealth() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(any(String.class), eq(Long.class), any()))
                .thenAnswer(invocation -> "PENDING".equals(invocation.getArgument(2)) ? 1L : 0L);
        when(jdbc.queryForObject(any(String.class), eq(Timestamp.class)))
                .thenReturn(Timestamp.from(Instant.now().minusSeconds(20 * 60)),
                        Timestamp.from(Instant.now().minusSeconds(91 * 60)));
        var sourceProperties = mock(AuditSourceEvidenceProperties.class);
        when(sourceProperties.isEnabled()).thenReturn(true);
        new ApplicationContextRunner()
                .withInitializer(context -> context.getBeanFactory()
                        .setConversionService(ApplicationConversionService.getSharedInstance()))
                .withPropertyValues("roomiq.audit.outbox.health.warn-after=10m",
                        "roomiq.audit.outbox.health.down-after=90m")
                .withBean(JdbcTemplate.class, () -> jdbc)
                .withBean(MeterRegistry.class, SimpleMeterRegistry::new)
                .withBean(AuditSourceEvidenceProperties.class, () -> sourceProperties)
                .withUserConfiguration(AuditOutboxObservability.class)
                .run(context -> {
                    var observability = context.getBean(AuditOutboxObservability.class);
                    assertThat(observability.health().getStatus().getCode()).isEqualTo("DEGRADED");
                    assertThat(observability.health().getStatus().getCode()).isEqualTo("DOWN");
                });
    }
}
