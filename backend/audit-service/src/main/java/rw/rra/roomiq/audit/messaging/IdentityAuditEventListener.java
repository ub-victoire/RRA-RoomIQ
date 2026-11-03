package rw.rra.roomiq.audit.messaging;

import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import rw.rra.roomiq.audit.domain.IdentityAuditEventCodec;
import rw.rra.roomiq.audit.domain.IdentityAuditEventProcessor;

import java.util.Objects;

@Component
@ConditionalOnProperty(name = "roomiq.audit.identity.events.enabled", havingValue = "true", matchIfMissing = true)
public class IdentityAuditEventListener {
    private final IdentityAuditEventCodec codec;
    private final IdentityAuditEventProcessor processor;

    public IdentityAuditEventListener(IdentityAuditEventCodec codec, IdentityAuditEventProcessor processor) {
        this.codec = codec;
        this.processor = processor;
    }

    @RabbitListener(queues = IdentityAuditMessagingConfiguration.QUEUE)
    public void receive(Message message) {
        if (!IdentityAuditMessagingConfiguration.ROUTING_KEY.equals(
                message.getMessageProperties().getReceivedRoutingKey())) {
            throw new IllegalArgumentException("Identity audit event routing key is invalid");
        }
        var event = codec.decode(message.getBody());
        if (!Objects.equals(message.getMessageProperties().getMessageId(), event.eventId().toString())) {
            throw new IllegalArgumentException("Identity audit message identifier does not match its event");
        }
        processor.process(event);
    }
}
