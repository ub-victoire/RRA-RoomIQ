package rw.rra.roomiq.audit.messaging;

import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageProperties;
import rw.rra.roomiq.audit.domain.NotificationTemplateAuditEventCodec;
import rw.rra.roomiq.audit.domain.NotificationTemplateAuditEventProcessor;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class NotificationTemplateAuditEventListenerTests {
    private final NotificationTemplateAuditEventProcessor processor =
            mock(NotificationTemplateAuditEventProcessor.class);
    private final NotificationTemplateAuditEventListener listener = new NotificationTemplateAuditEventListener(
            new NotificationTemplateAuditEventCodec(new ObjectMapper()), processor);

    @Test
    void processesMessagesWithExpectedRoutingAndMatchingBrokerEventId() {
        UUID eventId = UUID.randomUUID();

        listener.receive(message(AuditMessagingConfiguration.ROUTING_KEY, eventId.toString(), eventJson(eventId)));

        verify(processor).process(org.mockito.ArgumentMatchers.argThat(event -> event.eventId().equals(eventId)));
    }

    @Test
    void rejectsMissingOrMismatchedMessageIdAndUnexpectedRoutingBeforeProcessing() {
        UUID eventId = UUID.randomUUID();

        assertThatThrownBy(() -> listener.receive(message(AuditMessagingConfiguration.ROUTING_KEY, null,
                eventJson(eventId))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("identifier");
        assertThatThrownBy(() -> listener.receive(message(AuditMessagingConfiguration.ROUTING_KEY,
                UUID.randomUUID().toString(), eventJson(eventId))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("identifier");
        assertThatThrownBy(() -> listener.receive(message("audit.identity", eventId.toString(),
                eventJson(eventId))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("routing key");

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
                  "eventType":"NotificationTemplateCreated",
                  "timestamp":"2026-11-01T10:00:00.123456Z",
                  "sourceService":"notification-service",
                  "actorUserId":"%s",
                  "resourceType":"NOTIFICATION_TEMPLATE",
                  "resourceId":"%s",
                  "correlationId":"correlation-1",
                  "metadata":{
                    "code":"BookingApproved",
                    "version":2,
                    "channel":"IN_APP",
                    "changedFields":["code","version","channel"]
                  }
                }
                """.formatted(eventId, UUID.randomUUID(), UUID.randomUUID());
    }
}
