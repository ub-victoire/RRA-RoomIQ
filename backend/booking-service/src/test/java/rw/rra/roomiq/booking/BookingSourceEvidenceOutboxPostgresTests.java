package rw.rra.roomiq.booking;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import rw.rra.roomiq.booking.domain.entity.BookingRequest;
import rw.rra.roomiq.booking.domain.enums.BookingRequestStatus;
import rw.rra.roomiq.booking.domain.enums.BookingRequestType;
import rw.rra.roomiq.booking.domain.repository.BookingRequestRepository;
import rw.rra.roomiq.booking.domain.repository.BookingSourceEvidenceOutboxRepository;
import rw.rra.roomiq.booking.domain.service.BookingSourceEvidenceOutboxService;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Testcontainers
class BookingSourceEvidenceOutboxPostgresTests {
    @Container
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine");

    @DynamicPropertySource
    static void configurePostgres(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("roomiq.booking.event-relay.enabled", () -> "false");
        registry.add("roomiq.booking.source-evidence.enabled", () -> "false");
        registry.add("roomiq.booking.source-evidence.mappings.ADMIN_DIRECT_BOOKING.category-code",
                () -> "TEST_BOOKING_CATEGORY");
        registry.add("roomiq.booking.source-evidence.mappings.ADMIN_DIRECT_BOOKING.start-basis-code",
                () -> "RECORD_CREATED_AT");
    }

    @Autowired
    private BookingRequestRepository bookingRequests;

    @Autowired
    private BookingSourceEvidenceOutboxRepository evidenceOutboxRepository;

    @Autowired
    private BookingSourceEvidenceOutboxService evidenceOutboxService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Test
    @Transactional
    void persistsExplicitlyMappedEvidenceAndSupersedingRevisionWithBookingUpdates() {
        Instant now = Instant.now();
        BookingRequest request = saveRequest(BookingRequestType.ADMIN_DIRECT_BOOKING, now);
        evidenceOutboxService.record(request, now, null);
        var first = evidenceOutboxRepository.findByRevision(request.getId(), 1).orElseThrow();

        assertThat(first.requestType()).isEqualTo("ADMIN_DIRECT_BOOKING");
        assertThat(first.categoryCode()).isEqualTo("TEST_BOOKING_CATEGORY");
        assertThat(first.startBasisCode()).isEqualTo("RECORD_CREATED_AT");
        assertThat(first.authoritativeStartAt()).isEqualTo(first.recordCreatedAt());
        assertThat(first.currentLifecycleStatus()).isEqualTo("DRAFT");
        assertThat(first.observedAt()).isNotNull();

        assertThat(request.submitForApproval()).isTrue();
        bookingRequests.saveAndFlush(request);
        Instant submittedAt = now.plusSeconds(5);
        evidenceOutboxService.record(request, submittedAt, null);
        var correction = evidenceOutboxRepository.findByRevision(request.getId(), 2).orElseThrow();

        assertThat(correction.currentLifecycleStatus()).isEqualTo("PENDING_APPROVAL");
        assertThat(correction.supersedesEventId()).isEqualTo(first.eventId());
        assertThat(correction.sourceRevision()).isEqualTo(2);
    }

    @Test
    @Transactional
    void leavesUnmappedBookingTypesBlockedRatherThanInventingACategory() {
        Instant now = Instant.now();
        BookingRequest request = saveRequest(BookingRequestType.SECRETARY_REQUEST, now);
        evidenceOutboxService.record(request, now, null);
        var entry = evidenceOutboxRepository.findByRevision(request.getId(), 1).orElseThrow();
        evidenceOutboxService.applyCurrentMapping(entry);

        String deliveryStatus = jdbcTemplate.queryForObject("""
                SELECT delivery_status FROM booking_source_evidence_outbox WHERE booking_request_id = ?
                """, String.class, request.getId());
        String diagnostic = jdbcTemplate.queryForObject("""
                SELECT last_error_code FROM booking_source_evidence_outbox WHERE booking_request_id = ?
                """, String.class, request.getId());

        assertThat(deliveryStatus).isEqualTo("CONFIGURATION_BLOCKED");
        assertThat(diagnostic).isEqualTo("BOOKING_CATEGORY_MAPPING_MISSING");
    }

    @Test
    void bookingAndEvidenceOutboxCommitOrRollBackTogether() {
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        AtomicReference<UUID> bookingId = new AtomicReference<>();
        Instant now = Instant.now();

        assertThatThrownBy(() -> transaction.execute(status -> {
            BookingRequest request = bookingRequests.saveAndFlush(
                    newRequest(BookingRequestType.ADMIN_DIRECT_BOOKING, now));
            bookingId.set(request.getId());
            evidenceOutboxService.record(request, now, null);
            throw new IllegalStateException("test rollback");
        })).isInstanceOf(IllegalStateException.class).hasMessage("test rollback");

        Integer bookingCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM booking_request WHERE id = ?", Integer.class, bookingId.get());
        Integer evidenceCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM booking_source_evidence_outbox WHERE booking_request_id = ?",
                Integer.class, bookingId.get());
        assertThat(bookingCount).isZero();
        assertThat(evidenceCount).isZero();
    }

    private BookingRequest saveRequest(BookingRequestType requestType, Instant now) {
        return bookingRequests.saveAndFlush(newRequest(requestType, now));
    }

    private static BookingRequest newRequest(BookingRequestType requestType, Instant now) {
        return new BookingRequest("BR-EVIDENCE-" + UUID.randomUUID(), requestType, UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), null, "Evidence test booking",
                null, now.plusSeconds(3_600), now.plusSeconds(7_200), 1, false,
                BookingRequestStatus.DRAFT, null, now);
    }
}
