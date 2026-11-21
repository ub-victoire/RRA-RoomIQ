package rw.rra.roomiq.audit.domain;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import rw.rra.roomiq.audit.integration.AuditSourceEvidenceProperties;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;

@Component
public class AuditOutboxObservability {
    private static final Logger log = LoggerFactory.getLogger(AuditOutboxObservability.class);
    private final JdbcTemplate jdbc;
    private final AuditSourceEvidenceProperties sourceEvidence;
    private final Duration warnAfter;
    private final Duration downAfter;
    private final Counter attempts;
    private final Counter retries;
    private final Counter terminal;
    private final Counter reclaims;

    public AuditOutboxObservability(JdbcTemplate jdbc, MeterRegistry registry,
                                    AuditSourceEvidenceProperties sourceEvidence,
                                    @Value("${roomiq.audit.outbox.health.warn-after:15m}") Duration warnAfter,
                                    @Value("${roomiq.audit.outbox.health.down-after:1h}") Duration downAfter) {
        if (warnAfter.isZero() || warnAfter.isNegative() || downAfter.compareTo(warnAfter) <= 0) {
            throw new IllegalArgumentException("Outbox health thresholds must be positive and down must exceed warn");
        }
        this.jdbc = jdbc;
        this.sourceEvidence = sourceEvidence;
        this.warnAfter = warnAfter;
        this.downAfter = downAfter;
        this.attempts = registry.counter("roomiq.audit.outbox.delivery.attempts");
        this.retries = registry.counter("roomiq.audit.outbox.retries");
        this.terminal = registry.counter("roomiq.audit.outbox.terminal");
        this.reclaims = registry.counter("roomiq.audit.outbox.lease.reclaims");
        for (var status : Map.of("PENDING", "PENDING", "DELIVERED", "DELIVERED",
                "RECONCILIATION_REQUIRED", "RECONCILIATION_REQUIRED",
                "BLOCKED", "CONFIGURATION_BLOCKED").entrySet()) {
            Gauge.builder("roomiq.audit.outbox.records", this, metric -> metric.count(status.getValue()))
                    .tag("status", status.getKey()).register(registry);
        }
        Gauge.builder("roomiq.audit.outbox.oldest.pending.seconds", this,
                AuditOutboxObservability::oldestPendingSeconds).register(registry);
    }

    public void deliveryAttempt() {
        attempts.increment();
    }

    void retryCommitted() {
        afterCommit(() -> retries.increment());
    }

    void terminalCommitted() {
        afterCommit(() -> terminal.increment());
    }

    void leasesRecovered(long count, long terminalCount) {
        if (count > 0) {
            afterCommit(() -> {
                reclaims.increment(count);
                terminal.increment(terminalCount);
                retries.increment(count - terminalCount);
            });
        }
    }

    private static void afterCommit(Runnable action) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    action.run();
                }
            });
        } else {
            action.run();
        }
    }

    private long count(String status) {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM audit_source_evidence_outbox WHERE delivery_status = ?",
                Long.class, status);
        return count;
    }

    private double oldestPendingSeconds() {
        Timestamp oldest = jdbc.queryForObject(
                """
                SELECT MIN(next_attempt_at) FROM audit_source_evidence_outbox
                WHERE delivery_status = 'PENDING' AND claim_token IS NULL
                  AND next_attempt_at <= CURRENT_TIMESTAMP
                """,
                Timestamp.class);
        return oldest == null ? 0 : Math.max(0, Duration.between(oldest.toInstant(), Instant.now()).toMillis() / 1000.0);
    }

    Health health() {
        if (!sourceEvidence.isEnabled()) {
            return Health.up().withDetail("deliveryEnabled", false).build();
        }
        try {
            long pending = count("PENDING");
            long reconciliation = count("RECONCILIATION_REQUIRED");
            long blocked = count("CONFIGURATION_BLOCKED");
            double oldest = oldestPendingSeconds();
            Health.Builder builder = oldest >= downAfter.toMillis() / 1000.0 ? Health.down()
                    : reconciliation > 0 || oldest >= warnAfter.toMillis() / 1000.0
                    ? Health.status("DEGRADED") : Health.up();
            return builder.withDetail("deliveryEnabled", true)
                    .withDetail("pending", pending)
                    .withDetail("reconciliationRequired", reconciliation)
                    .withDetail("blocked", blocked)
                    .withDetail("oldestPendingSeconds", oldest)
                    .withDetail("warnAfterSeconds", warnAfter.toMillis() / 1000.0)
                    .withDetail("downAfterSeconds", downAfter.toMillis() / 1000.0)
                    .build();
        } catch (DataAccessException exception) {
            log.warn("Audit outbox health query failed errorCode=AUDIT_OUTBOX_HEALTH_QUERY_FAILED");
            return Health.down().withDetail("database", "unavailable").build();
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class HealthConfiguration {
        @Bean
        HealthIndicator outboxHealthIndicator(AuditOutboxObservability observability) {
            return observability::health;
        }
    }
}
