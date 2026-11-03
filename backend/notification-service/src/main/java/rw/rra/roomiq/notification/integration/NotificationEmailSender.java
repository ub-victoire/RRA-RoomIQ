package rw.rra.roomiq.notification.integration;

import java.util.UUID;

public interface NotificationEmailSender {
    String send(UUID notificationId, String recipientAddress, String subject, String htmlBody);
}
