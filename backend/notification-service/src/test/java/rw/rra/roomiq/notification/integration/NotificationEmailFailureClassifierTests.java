package rw.rra.roomiq.notification.integration;

import org.eclipse.angus.mail.smtp.SMTPSendFailedException;
import org.junit.jupiter.api.Test;
import org.springframework.mail.MailAuthenticationException;
import org.springframework.mail.MailSendException;

import java.net.SocketTimeoutException;

import static org.assertj.core.api.Assertions.assertThat;

class NotificationEmailFailureClassifierTests {
    private final NotificationEmailFailureClassifier classifier =
            new NotificationEmailFailureClassifier(
                    new NotificationEmailRetryProperties(5, 60_000, 2, 480_000, ""));

    @Test
    void classifiesSmtpFourHundredResponsesAsRetryableAndFiveHundredAsPermanent() {
        var temporary = classifier.classify(new MailSendException(
                "safe", new SMTPSendFailedException("temporary", 451, "temporary", null, null, null, null)));
        var permanent = classifier.classify(new MailSendException(
                "safe", new SMTPSendFailedException("permanent", 550, "permanent", null, null, null, null)));

        assertThat(temporary.retryable()).isTrue();
        assertThat(temporary.reasonCode()).isEqualTo("SMTP_RESPONSE_451");
        assertThat(permanent.retryable()).isFalse();
        assertThat(permanent.reasonCode()).isEqualTo("SMTP_RESPONSE_550");
    }

    @Test
    void retriesNetworkTimeoutsButDoesNotRetryAuthenticationFailures() {
        var timeout = classifier.classify(new MailSendException(
                "safe", new SocketTimeoutException("private network detail")));
        var authentication = classifier.classify(new MailAuthenticationException("private credential detail"));

        assertThat(timeout.retryable()).isTrue();
        assertThat(timeout.reasonCode()).isEqualTo("SMTP_TEMPORARY_NETWORK_FAILURE");
        assertThat(authentication.retryable()).isFalse();
        assertThat(authentication.reasonCode()).isEqualTo("SMTP_CONFIGURATION_INVALID");
    }
}
