package rw.rra.roomiq.audit.messaging;

import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import rw.rra.roomiq.audit.domain.NotificationTemplateAuditEventCodec;
import rw.rra.roomiq.audit.domain.NotificationTemplateAuditEventProcessor;

import java.util.Objects;

@Component
@ConditionalOnProperty(name = "roomiq.audit.notification.events.enabled", havingValue = "true",
        matchIfMissing = true)
public class NotificationTemplateAuditEventListener {
    private final NotificationTemplateAuditEventCodec codec;
    private final NotificationTemplateAuditEventProcessor processor;

    public NotificationTemplateAuditEventListener(NotificationTemplateAuditEventCodec codec,
                                                   NotificationTemplateAuditEventProcessor processor) {
        this.codec = codec;
        this.processor = processor;
    }

    @RabbitListener(queues = AuditMessagingConfiguration.QUEUE)
    public void receive(Message message) {
        if (!AuditMessagingConfiguration.ROUTING_KEY.equals(
                message.getMessageProperties().getReceivedRoutingKey())) {
            throw new IllegalArgumentException("Notification audit event routing key is invalid");
        }
        var event = codec.decode(message.getBody());
        String messageId = message.getMessageProperties().getMessageId();
        if (messageId != null && !Objects.equals(messageId, event.eventId().toString())) {
            throw new IllegalArgumentException("Notification audit message identifier does not match its event");
        }
        processor.process(event);
    }
}
