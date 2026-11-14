package rw.rra.roomiq.booking;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import rw.rra.roomiq.booking.domain.entity.BookingRecordLegalHold;
import rw.rra.roomiq.booking.domain.entity.BookingRequest;
import rw.rra.roomiq.booking.config.BookingSourceHoldAuthorizationInterceptor;
import rw.rra.roomiq.booking.domain.enums.BookingRequestStatus;
import rw.rra.roomiq.booking.domain.enums.BookingRequestType;
import rw.rra.roomiq.booking.domain.repository.BookingRecordLegalHoldRepository;
import rw.rra.roomiq.booking.domain.repository.BookingRequestRepository;
import rw.rra.roomiq.booking.domain.service.BookingSourceHoldService;
import rw.rra.roomiq.booking.integration.BookingHoldAcknowledgement;
import rw.rra.roomiq.booking.integration.BookingHoldInstruction;
import rw.rra.roomiq.common.web.DomainException;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Testcontainers
class BookingSourceHoldPostgresTests {
    @Container
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine");

    @DynamicPropertySource
    static void configurePostgres(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("roomiq.booking.event-relay.enabled", () -> "false");
        registry.add("roomiq.booking.source-evidence.enabled", () -> "false");
    }

    @Autowired
    private BookingSourceHoldService holds;

    @Autowired
    private BookingRequestRepository requests;

    @Autowired
    private BookingRecordLegalHoldRepository holdRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private BookingSourceHoldAuthorizationInterceptor authorizationInterceptor;

    @Test
    void appliesAndReleasesOnlyOrderedAuthenticatedInstructionsIdempotently() {
        BookingRequest request = saveRequest();
        UUID holdId = UUID.randomUUID();
        BookingHoldInstruction apply = instruction(holdId, request.getId(), 1, "APPLY", UUID.randomUUID());

        BookingHoldAcknowledgement accepted = holds.receive(apply, "records-lifecycle-service");
        BookingHoldAcknowledgement duplicate = holds.receive(apply, "records-lifecycle-service");

        assertThat(accepted.outcome()).isEqualTo(BookingHoldAcknowledgement.Outcome.ACCEPTED);
        assertThat(accepted.enforcementStatus()).isEqualTo("ACTIVE");
        assertThat(duplicate.outcome()).isEqualTo(BookingHoldAcknowledgement.Outcome.DUPLICATE);
        assertThat(holdRepository.findByHoldId(holdId)).get()
                .extracting(BookingRecordLegalHold::getStatus)
                .isEqualTo(BookingRecordLegalHold.Status.ACTIVE);
        assertThatThrownBy(() -> holds.requireNoActiveHold(request.getId()))
                .isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> jdbcTemplate.update("""
                UPDATE booking_request SET status = 'CANCELLED' WHERE id = ?
                """, request.getId())).isInstanceOf(RuntimeException.class);

        assertThat(holds.receive(instruction(holdId, request.getId(), 3, "RELEASE", UUID.randomUUID()),
                "records-lifecycle-service").outcome())
                .isEqualTo(BookingHoldAcknowledgement.Outcome.RECONCILIATION_REQUIRED);

        BookingHoldInstruction release = instruction(holdId, request.getId(), 2, "RELEASE", UUID.randomUUID());
        BookingHoldAcknowledgement released = holds.receive(release, "records-lifecycle-service");

        assertThat(released.outcome()).isEqualTo(BookingHoldAcknowledgement.Outcome.ACCEPTED);
        assertThat(released.enforcementStatus()).isEqualTo("RELEASED");
        assertThat(holds.receive(release, "records-lifecycle-service").outcome())
                .isEqualTo(BookingHoldAcknowledgement.Outcome.DUPLICATE);
        assertThat(holds.receive(instruction(holdId, request.getId(), 1, "APPLY", UUID.randomUUID()),
                "records-lifecycle-service").outcome()).isEqualTo(BookingHoldAcknowledgement.Outcome.STALE);
        assertThat(holdRepository.findByHoldId(holdId)).get()
                .extracting(BookingRecordLegalHold::getStatus)
                .isEqualTo(BookingRecordLegalHold.Status.RELEASED);
        assertThatThrownBy(() -> holds.receive(instruction(holdId, request.getId(), 3, "APPLY",
                UUID.randomUUID()), "untrusted-service"))
                .isInstanceOf(DomainException.class);
    }

    @Test
    void holdInstructionAndReceiptRollBackTogetherAndInternalEndpointRequiresTrustedToken() {
        BookingRequest request = saveRequest();
        UUID holdId = UUID.randomUUID();
        BookingHoldInstruction instruction = instruction(holdId, request.getId(), 1, "APPLY", UUID.randomUUID());

        assertThatThrownBy(() -> new TransactionTemplate(transactionManager).execute(status -> {
            holds.receive(instruction, "records-lifecycle-service");
            throw new IllegalStateException("test rollback");
        })).isInstanceOf(IllegalStateException.class).hasMessage("test rollback");
        assertThat(holdRepository.findByHoldId(holdId)).isEmpty();
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM booking_legal_hold_instruction_receipt WHERE hold_id = ?
                """, Integer.class, holdId)).isZero();

        MockHttpServletRequest httpRequest = new MockHttpServletRequest();
        assertThatThrownBy(() -> authorizationInterceptor.preHandle(
                httpRequest, new MockHttpServletResponse(), new Object()))
                .isInstanceOf(DomainException.class);
        httpRequest.addHeader("Authorization", "Bearer invalid.token.value");
        assertThatThrownBy(() -> authorizationInterceptor.preHandle(
                httpRequest, new MockHttpServletResponse(), new Object()))
                .isInstanceOf(DomainException.class);
    }

    private BookingRequest saveRequest() {
        Instant now = Instant.now();
        return requests.saveAndFlush(new BookingRequest("BR-HOLD-" + UUID.randomUUID(),
                BookingRequestType.ADMIN_DIRECT_BOOKING, UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), null, "Hold integration test", null,
                now.plusSeconds(3_600), now.plusSeconds(7_200), 1, false,
                BookingRequestStatus.DRAFT, null, now));
    }

    private static BookingHoldInstruction instruction(
            UUID holdId, UUID recordId, long revision, String operation, UUID instructionId) {
        return new BookingHoldInstruction(1, instructionId, holdId, revision, operation,
                "records-lifecycle-service", "BOOKING_REQUEST", recordId.toString(),
                "a".repeat(64), Instant.now(), "test-correlation");
    }
}
