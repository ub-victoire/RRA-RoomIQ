package rw.rra.roomiq.room.domain.repository;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.postgresql.util.PGobject;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import rw.rra.roomiq.room.domain.entity.RoomEventOutboxEntry;
import rw.rra.roomiq.room.integration.CloudinaryPhotoStorage;
import rw.rra.roomiq.room.integration.OrganizationDirectoryClient;
import rw.rra.roomiq.room.integration.RoomPhotoAuthorizationClient;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = "room.events.relay.enabled=false")
@Testcontainers
class RoomEventOutboxRepositoryPostgresTests {
    @Container
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine");

    @DynamicPropertySource
    static void configurePostgres(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("room.events.relay.enabled", () -> "false");
    }

    @Autowired
    private RoomEventOutboxRepository repository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @MockitoBean
    private OrganizationDirectoryClient organizationDirectoryClient;

    @MockitoBean
    private CloudinaryPhotoStorage cloudinaryPhotoStorage;

    @MockitoBean
    private RoomPhotoAuthorizationClient roomPhotoAuthorizationClient;

    @BeforeEach
    void clearOutbox() {
        jdbcTemplate.update("DELETE FROM room_event_outbox");
    }

    @Test
    void concurrentWorkersCannotClaimTheSamePendingRoomEvent() throws Exception {
        Instant now = Instant.now();
        UUID eventId = UUID.randomUUID();
        PGobject payload = new PGobject();
        payload.setType("jsonb");
        payload.setValue("{\"eventId\":\"" + eventId + "\"}");
        repository.saveAndFlush(new RoomEventOutboxEntry(eventId, "RoomStatusChanged", 1, "ROOM",
                UUID.randomUUID(), 1, payload, now, now));

        CountDownLatch firstWorkerLockedRow = new CountDownLatch(1);
        CountDownLatch releaseFirstWorker = new CountDownLatch(1);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        try {
            Future<List<RoomEventOutboxEntry>> firstClaim = executor.submit(() -> transaction.execute(status -> {
                List<RoomEventOutboxEntry> claimed = repository.claimDue(now);
                firstWorkerLockedRow.countDown();
                await(releaseFirstWorker);
                return claimed;
            }));
            assertThat(firstWorkerLockedRow.await(10, TimeUnit.SECONDS)).isTrue();

            List<?> secondClaim = transaction.execute(status -> repository.claimDue(now));

            assertThat(secondClaim).isEmpty();
            releaseFirstWorker.countDown();
            assertThat(firstClaim.get(10, TimeUnit.SECONDS))
                    .extracting(RoomEventOutboxEntry::getEventId)
                    .containsExactly(eventId);
        } finally {
            releaseFirstWorker.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void rolledBackClaimLeavesUnpublishedEventAvailableToAnotherWorker() throws SQLException {
        Instant now = Instant.now();
        UUID eventId = UUID.randomUUID();
        PGobject payload = new PGobject();
        payload.setType("jsonb");
        payload.setValue("{\"eventId\":\"" + eventId + "\"}");
        repository.saveAndFlush(new RoomEventOutboxEntry(eventId, "RoomStatusChanged", 1, "ROOM",
                UUID.randomUUID(), 1, payload, now, now));
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);

        List<?> interruptedClaim = transaction.execute(status -> {
            List<RoomEventOutboxEntry> claimed = repository.claimDue(now);
            status.setRollbackOnly();
            return claimed;
        });
        List<RoomEventOutboxEntry> subsequentClaim = transaction.execute(status -> repository.claimDue(now));

        assertThat(interruptedClaim).hasSize(1);
        assertThat(subsequentClaim).extracting(RoomEventOutboxEntry::getEventId).containsExactly(eventId);
        assertThat(subsequentClaim.getFirst().getPublishedAt()).isNull();
    }

    @Test
    void eventWritesRemainServiceLocalWithoutForeignKeys() throws SQLException {
        UUID eventId = UUID.randomUUID();
        PGobject payload = new PGobject();
        payload.setType("jsonb");
        payload.setValue("{\"eventId\":\"" + eventId + "\"}");
        Instant now = Instant.now();
        repository.saveAndFlush(new RoomEventOutboxEntry(eventId, "RoomStatusChanged", 1, "ROOM",
                UUID.randomUUID(), 1, payload, now, now));

        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM information_schema.table_constraints
                WHERE table_name = 'room_event_outbox' AND constraint_type = 'FOREIGN KEY'
                """, Integer.class)).isZero();
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Timed out waiting to release the Room outbox row lock");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while holding the Room outbox row lock", exception);
        }
    }
}
