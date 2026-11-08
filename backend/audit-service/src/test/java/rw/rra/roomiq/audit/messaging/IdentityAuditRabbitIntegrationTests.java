package rw.rra.roomiq.audit.messaging;

import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.rabbitmq.RabbitMQContainer;
import rw.rra.roomiq.audit.domain.AuditEventRepository;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@SpringBootTest(properties = {
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=validate",
        "roomiq.auth.jwt-secret=test-only-signing-key-at-least-32-bytes-long",
        "roomiq.audit.notification.events.enabled=true",
        "roomiq.audit.identity.events.enabled=true",
        "spring.rabbitmq.listener.simple.retry.max-attempts=3",
        "spring.rabbitmq.listener.simple.retry.initial-interval=20ms",
        "spring.rabbitmq.listener.simple.retry.multiplier=1",
        "spring.rabbitmq.listener.simple.retry.max-interval=20ms"
})
@Testcontainers
class IdentityAuditRabbitIntegrationTests {
    @Container
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine")
            .withInitScript("audit-test-roles.sql");

    @Container
    static final RabbitMQContainer rabbit = new RabbitMQContainer("rabbitmq:3.13-management-alpine");

    @DynamicPropertySource
    static void configureServices(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", () -> "roomiq_audit_app");
        registry.add("spring.datasource.password", () -> "audit-app-test-password");
        registry.add("spring.flyway.user", () -> "roomiq_audit_migrator");
        registry.add("spring.flyway.password", () -> "audit-migrator-test-password");
        registry.add("spring.rabbitmq.host", rabbit::getHost);
        registry.add("spring.rabbitmq.port", rabbit::getAmqpPort);
        registry.add("spring.rabbitmq.username", rabbit::getAdminUsername);
        registry.add("spring.rabbitmq.password", rabbit::getAdminPassword);
    }

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @Autowired
    private RabbitAdmin rabbitAdmin;

    @Autowired
    private AuditEventRepository repository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    private final HttpClient rabbitManagementClient = HttpClient.newHttpClient();

    @org.junit.jupiter.api.BeforeEach
    void clearQueues() {
        rabbitAdmin.purgeQueue(IdentityAuditMessagingConfiguration.QUEUE);
        rabbitAdmin.purgeQueue(IdentityAuditMessagingConfiguration.DEAD_LETTER_QUEUE);
        rabbitAdmin.purgeQueue(AuditMessagingConfiguration.QUEUE);
        rabbitAdmin.purgeQueue(AuditMessagingConfiguration.DEAD_LETTER_QUEUE);
    }

    @Test
    void commitsAuditBeforeSuccessfulDeliveryIsAcknowledged() {
        UUID eventId = UUID.randomUUID();
        send(IdentityAuditMessagingConfiguration.ROUTING_KEY, eventId, eventJson(eventId, "identity-service",
                "AUTHENTICATION_FAILED", "AUTHENTICATION", "DENIED", "null", "null", "null", "{}"));

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            assertThat(repository.findById(eventId)).isPresent();
            assertThat(rabbitAdmin.getQueueInfo(IdentityAuditMessagingConfiguration.QUEUE).getMessageCount())
                    .isZero();
        });
        assertThat(rabbitAdmin.getQueueInfo(IdentityAuditMessagingConfiguration.DEAD_LETTER_QUEUE).getMessageCount())
                .isZero();
    }

    @Test
    void duplicateBrokerDeliveryProducesOneAuditRecord() {
        UUID eventId = UUID.randomUUID();
        String payload = eventJson(eventId, "identity-service", "AUTHENTICATION_FAILED", "AUTHENTICATION",
                "DENIED", "null", "null", "null", "{}");
        send(IdentityAuditMessagingConfiguration.ROUTING_KEY, eventId, payload);
        send(IdentityAuditMessagingConfiguration.ROUTING_KEY, eventId, payload);

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            assertThat(repository.findById(eventId)).isPresent();
            assertThat(rabbitAdmin.getQueueInfo(IdentityAuditMessagingConfiguration.QUEUE).getMessageCount())
                    .isZero();
        });
    }

    @Test
    void deadLettersUnsupportedEventsAndUnauthorizedRoutingAfterBoundedRetry() {
        UUID eventId = UUID.randomUUID();
        send(IdentityAuditMessagingConfiguration.ROUTING_KEY, eventId, eventJson(eventId, "identity-service",
                "UNAPPROVED_EVENT", "USER", "SUCCESS", quote(UUID.randomUUID()), quote(UUID.randomUUID()),
                "null", "{}"));
        awaitDeadLetter(IdentityAuditMessagingConfiguration.DEAD_LETTER_QUEUE);
        assertThat(repository.findById(eventId)).isEmpty();

        UUID wrongRouteEventId = UUID.randomUUID();
        send(IdentityAuditMessagingConfiguration.QUEUE, wrongRouteEventId, eventJson(wrongRouteEventId,
                "identity-service", "AUTHENTICATION_FAILED", "AUTHENTICATION", "DENIED",
                "null", "null", "null", "{}"), "");
        awaitDeadLetter(IdentityAuditMessagingConfiguration.DEAD_LETTER_QUEUE);
        assertThat(repository.findById(wrongRouteEventId)).isEmpty();
    }

    @Test
    void notificationAuditCommitsBeforeAcknowledgementAndDeadLettersUnsupportedEvents() {
        UUID eventId = UUID.randomUUID();
        send(AuditMessagingConfiguration.ROUTING_KEY, eventId, notificationEventJson(eventId,
                "NotificationTemplateCreated"));

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            assertThat(repository.findById(eventId)).isPresent();
            assertThat(rabbitAdmin.getQueueInfo(AuditMessagingConfiguration.QUEUE).getMessageCount())
                    .isZero();
        });
        assertThat(rabbitAdmin.getQueueInfo(AuditMessagingConfiguration.DEAD_LETTER_QUEUE).getMessageCount())
                .isZero();

        UUID unsupportedEventId = UUID.randomUUID();
        send(AuditMessagingConfiguration.ROUTING_KEY, unsupportedEventId,
                notificationEventJson(unsupportedEventId, "NotificationTemplateDeleted"));
        awaitDeadLetter(AuditMessagingConfiguration.DEAD_LETTER_QUEUE);
        assertThat(repository.findById(unsupportedEventId)).isEmpty();
    }

    @Test
    void duplicateNotificationDeliveryAfterAcknowledgementPersistsOneAuditRecord() throws Exception {
        UUID eventId = UUID.randomUUID();
        String payload = notificationEventJson(eventId, "NotificationTemplateCreated");

        send(AuditMessagingConfiguration.ROUTING_KEY, eventId, payload);
        awaitNotificationEventPersistedAndAcknowledged(eventId);
        assertThat(queueStatus(AuditMessagingConfiguration.DEAD_LETTER_QUEUE)
                .path("messages_ready").asInt(-1)).isZero();
        assertThat(auditEventCount(eventId)).isEqualTo(1L);

        send(AuditMessagingConfiguration.ROUTING_KEY, eventId, payload);
        awaitNotificationEventPersistedAndAcknowledged(eventId);

        assertThat(auditEventCount(eventId)).isEqualTo(1L);
        assertThat(queueStatus(AuditMessagingConfiguration.DEAD_LETTER_QUEUE)
                .path("messages_ready").asInt(-1)).isZero();
        assertThat(queueStatus(AuditMessagingConfiguration.DEAD_LETTER_QUEUE)
                .path("messages_unacknowledged").asInt(-1)).isZero();
    }

    private void awaitNotificationEventPersistedAndAcknowledged(UUID eventId) {
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            assertThat(auditEventCount(eventId)).isEqualTo(1L);
            assertThat(queueStatus(AuditMessagingConfiguration.QUEUE)
                    .path("messages_ready").asInt(-1)).isZero();
            assertThat(queueStatus(AuditMessagingConfiguration.QUEUE)
                    .path("messages_unacknowledged").asInt(-1)).isZero();
        });
    }

    private long auditEventCount(UUID eventId) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM audit_event WHERE id = ?", Long.class, eventId);
    }

    private JsonNode queueStatus(String queue) throws IOException, InterruptedException {
        String credentials = rabbit.getAdminUsername() + ":" + rabbit.getAdminPassword();
        String authorization = Base64.getEncoder()
                .encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://" + rabbit.getHost() + ":"
                        + rabbit.getHttpPort() + "/api/queues/%2F/" + queue))
                .header("Authorization", "Basic " + authorization)
                .GET()
                .build();
        HttpResponse<String> response = rabbitManagementClient.send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        return objectMapper.readTree(response.body());
    }

    private void awaitDeadLetter(String deadLetterQueue) {
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(rabbitAdmin.getQueueInfo(deadLetterQueue)
                        .getMessageCount()).isGreaterThan(0));
        Message deadLetter = rabbitTemplate.receive(deadLetterQueue);
        assertThat(deadLetter).isNotNull();
    }

    private void send(String routingKey, UUID eventId, String payload) {
        send(routingKey, eventId, payload, IdentityAuditMessagingConfiguration.EVENTS_EXCHANGE);
    }

    private void send(String routingKey, UUID eventId, String payload, String exchange) {
        MessageProperties properties = new MessageProperties();
        properties.setMessageId(eventId.toString());
        properties.setContentType("application/json");
        properties.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
        rabbitTemplate.send(exchange, routingKey, new Message(payload.getBytes(StandardCharsets.UTF_8), properties));
    }

    private static String eventJson(UUID eventId, String source, String eventType, String resourceType,
                                    String outcome, String actorId, String resourceId, String scopeId,
                                    String metadata) {
        return """
                {
                  "eventId":"%s",
                  "schemaVersion":1,
                  "eventType":"%s",
                  "timestamp":"2026-11-03T07:00:00Z",
                  "sourceService":"%s",
                  "actorUserId":%s,
                  "resourceType":"%s",
                  "resourceId":%s,
                  "scopeOfficeBuildingId":%s,
                  "outcome":"%s",
                  "correlationId":"correlation-1",
                  "metadata":%s
                }
                """.formatted(eventId, eventType, source, actorId, resourceType, resourceId, scopeId, outcome,
                metadata);
    }

    private static String quote(UUID value) {
        return "\"" + value + "\"";
    }

    private static String notificationEventJson(UUID eventId, String eventType) {
        return """
                {
                  "eventId":"%s",
                  "schemaVersion":1,
                  "eventType":"%s",
                  "timestamp":"2026-11-03T07:00:00Z",
                  "sourceService":"notification-service",
                  "actorUserId":"%s",
                  "resourceType":"NOTIFICATION_TEMPLATE",
                  "resourceId":"%s",
                  "correlationId":"notification-correlation",
                  "metadata":{
                    "code":"BookingApproved",
                    "version":2,
                    "channel":"IN_APP",
                    "changedFields":["code","version"]
                  }
                }
                """.formatted(eventId, eventType, UUID.randomUUID(), UUID.randomUUID());
    }
}
