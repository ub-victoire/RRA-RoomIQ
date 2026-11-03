package rw.rra.roomiq.notification.integration;

import com.icegreen.greenmail.util.GreenMail;
import com.icegreen.greenmail.util.ServerSetup;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.Test;
import org.springframework.mail.javamail.JavaMailSenderImpl;

import java.util.Properties;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class SmtpNotificationEmailSenderTests {
    @Test
    void sendsUtf8HtmlEmailThroughAnInProcessSmtpRelay() throws Exception {
        GreenMail smtp = new GreenMail(new ServerSetup(0, "127.0.0.1", ServerSetup.PROTOCOL_SMTP));
        smtp.start();
        try {
            JavaMailSenderImpl mailSender = new JavaMailSenderImpl();
            mailSender.setHost("127.0.0.1");
            mailSender.setPort(smtp.getSmtp().getPort());
            mailSender.setProtocol("smtp");
            mailSender.setDefaultEncoding("UTF-8");
            Properties mailProperties = mailSender.getJavaMailProperties();
            mailProperties.put("mail.smtp.auth", "false");
            mailProperties.put("mail.smtp.starttls.enable", "false");
            mailProperties.put("mail.smtp.connectiontimeout", "1000");
            mailProperties.put("mail.smtp.timeout", "1000");
            mailProperties.put("mail.smtp.writetimeout", "1000");
            NotificationEmailProperties properties = new NotificationEmailProperties(
                    true, "notifications@example.org", "RRA RoomIQ", "127.0.0.1",
                    smtp.getSmtp().getPort(), "", "", "STARTTLS",
                    false, true, true, false, 1000, 1000, 1000);
            SmtpNotificationEmailSender sender = new SmtpNotificationEmailSender(mailSender, properties);
            UUID notificationId = UUID.randomUUID();

            sender.send(notificationId, "recipient@example.org", "Booking approved",
                    "<p>Booking approved — room 12.</p>");

            assertThat(smtp.waitForIncomingEmail(2_000, 1)).isTrue();
            MimeMessage received = smtp.getReceivedMessages()[0];
            assertThat(received.getFrom()[0].toString()).contains("notifications@example.org");
            assertThat(received.getRecipients(MimeMessage.RecipientType.TO)[0].toString())
                    .contains("recipient@example.org");
            assertThat(received.getSubject()).isEqualTo("Booking approved");
            assertThat(received.getHeader("X-RoomIQ-Notification-Id")[0]).isEqualTo(notificationId.toString());
            assertThat(received.getContent().toString()).contains("Booking approved — room 12.");
        } finally {
            smtp.stop();
        }
    }
}
