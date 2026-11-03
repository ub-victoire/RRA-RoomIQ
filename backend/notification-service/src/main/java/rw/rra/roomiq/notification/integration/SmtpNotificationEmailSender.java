package rw.rra.roomiq.notification.integration;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.mail.MailException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

@Component
@ConditionalOnProperty(name = "roomiq.notification.email.enabled", havingValue = "true")
public class SmtpNotificationEmailSender implements NotificationEmailSender {
    private final JavaMailSender mailSender;
    private final NotificationEmailProperties emailProperties;

    public SmtpNotificationEmailSender(JavaMailSender mailSender,
                                       NotificationEmailProperties emailProperties) {
        this.mailSender = mailSender;
        this.emailProperties = emailProperties;
    }

    @Override
    public String send(UUID notificationId, String recipientAddress, String subject, String htmlBody) {
        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, false, StandardCharsets.UTF_8.name());
            helper.setFrom(new InternetAddress(emailProperties.senderAddress(),
                    emailProperties.senderName(), StandardCharsets.UTF_8.name()));
            helper.setTo(new InternetAddress(recipientAddress, true));
            helper.setSubject(subject);
            helper.setText(htmlBody, true);
            message.setHeader("X-RoomIQ-Notification-Id", notificationId.toString());
            mailSender.send(message);
            return null;
        } catch (MessagingException | java.io.UnsupportedEncodingException exception) {
            throw new NotificationEmailFormattingException(exception);
        } catch (MailException exception) {
            throw exception;
        }
    }

    public static final class NotificationEmailFormattingException extends RuntimeException {
        public NotificationEmailFormattingException(Throwable cause) {
            super(cause);
        }
    }
}
