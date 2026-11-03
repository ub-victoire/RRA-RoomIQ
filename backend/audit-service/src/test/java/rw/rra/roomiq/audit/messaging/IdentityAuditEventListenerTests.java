package rw.rra.roomiq.audit.messaging;

import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageProperties;
import rw.rra.roomiq.audit.domain.IdentityAuditEventCodec;
import rw.rra.roomiq.audit.domain.IdentityAuditEventProcessor;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class IdentityAuditEventListenerTests {
    private final IdentityAuditEventProcessor processor = mock(IdentityAuditEventProcessor.class);
    private final IdentityAuditEventListener listener = new IdentityAuditEventListener(
            new IdentityAuditEventCodec(new tools.jackson.databind.ObjectMapper()), processor);

    @Test
    void processesOnlyMessagesWithExpectedRoutingAndMatchingBrokerEventId() {
        UUID eventId = UUID.randomUUID();
        Message message = message(IdentityAuditMessagingConfiguration.ROUTING_KEY, eventId.toString(),
                eventJson(eventId));

        listener.receive(message);

        verify(processor).process(org.mockito.ArgumentMatchers.argThat(event -> event.eventId().equals(eventId)));
    }

    @Test
    void rejectsUnexpectedRoutingAndMismatchedMessageIdBeforeProcessing() {
        UUID eventId = UUID.randomUUID();
        assertThatThrownBy(() -> listener.receive(message("audit.booking", eventId.toString(),
                eventJson(eventId))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("routing key");
        assertThatThrownBy(() -> listener.receive(message(IdentityAuditMessagingConfiguration.ROUTING_KEY,
                UUID.randomUUID().toString(), eventJson(eventId))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("identifier");

        verifyNoInteractions(processor);
    }

    private static Message message(String routingKey, String messageId, String body) {
        MessageProperties properties = new MessageProperties();
        properties.setReceivedRoutingKey(routingKey);
        properties.setMessageId(messageId);
        return MessageBuilder.withBody(body.getBytes(StandardCharsets.UTF_8))
                .andProperties(properties)
                .build();
    }

    private static String eventJson(UUID eventId) {
        return """
                {
                  "eventId":"%s",
                  "schemaVersion":1,
                  "eventType":"AUTHENTICATION_FAILED",
                  "timestamp":"2026-11-03T07:00:00Z",
                  "sourceService":"identity-service",
                  "actorUserId":null,
                  "resourceType":"AUTHENTICATION",
                  "resourceId":null,
                  "scopeOfficeBuildingId":null,
                  "outcome":"DENIED",
                  "correlationId":"correlation-1",
                  "metadata":{}
                }
                """.formatted(eventId);
    }
}
