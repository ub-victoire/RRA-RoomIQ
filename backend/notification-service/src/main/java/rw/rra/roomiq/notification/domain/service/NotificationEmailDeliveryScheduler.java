package rw.rra.roomiq.notification.domain.service;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "roomiq.notification.email.enabled", havingValue = "true")
public class NotificationEmailDeliveryScheduler {
    private final NotificationEmailDeliveryService deliveryService;

    public NotificationEmailDeliveryScheduler(NotificationEmailDeliveryService deliveryService) {
        this.deliveryService = deliveryService;
    }

    @Scheduled(fixedDelayString = "${roomiq.notification.email.fixed-delay-ms:1000}")
    public void deliverPendingEmails() {
        deliveryService.deliverPendingEmails();
    }
}
