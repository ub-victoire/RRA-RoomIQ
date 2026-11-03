package rw.rra.roomiq.notification.integration;

import org.eclipse.angus.mail.smtp.SMTPAddressFailedException;
import org.eclipse.angus.mail.smtp.SMTPSendFailedException;
import org.springframework.mail.MailAuthenticationException;
import org.springframework.mail.MailException;
import org.springframework.mail.MailPreparationException;

import java.net.ConnectException;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

public class NotificationEmailFailureClassifier {
    private final NotificationEmailRetryProperties retryProperties;

    public NotificationEmailFailureClassifier(NotificationEmailRetryProperties retryProperties) {
        this.retryProperties = retryProperties;
    }

    public Classification classify(MailException exception) {
        if (exception instanceof MailAuthenticationException || exception instanceof MailPreparationException) {
            return new Classification(false, "SMTP_CONFIGURATION_INVALID");
        }

        for (Throwable cause : causeChain(exception)) {
            if (cause instanceof SMTPSendFailedException smtpException) {
                return smtpResponse(smtpException.getReturnCode());
            }
            if (cause instanceof SMTPAddressFailedException smtpException) {
                return smtpResponse(smtpException.getReturnCode());
            }
            if (cause instanceof MailAuthenticationException || cause instanceof MailPreparationException) {
                return new Classification(false, "SMTP_CONFIGURATION_INVALID");
            }
            if (cause instanceof SocketTimeoutException || cause instanceof ConnectException
                    || cause instanceof SocketException || cause instanceof UnknownHostException) {
                return new Classification(true, "SMTP_TEMPORARY_NETWORK_FAILURE");
            }
        }

        return new Classification(true, "SMTP_TEMPORARY_PROVIDER_FAILURE");
    }

    private Classification smtpResponse(int responseCode) {
        boolean retryable = retryProperties.isRetryableSmtpResponseCode(responseCode);
        return new Classification(retryable, "SMTP_RESPONSE_" + responseCode);
    }

    private static List<Throwable> causeChain(Throwable root) {
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        List<Throwable> causes = new ArrayList<>();
        Deque<Throwable> pending = new ArrayDeque<>();
        pending.push(root);
        while (!pending.isEmpty()) {
            Throwable next = pending.pop();
            if (!seen.add(next)) {
                continue;
            }
            causes.add(next);
            if (next.getCause() != null) {
                pending.push(next.getCause());
            }
            if (next instanceof jakarta.mail.MessagingException messagingException
                    && messagingException.getNextException() != null) {
                pending.push(messagingException.getNextException());
            }
            if (next instanceof org.springframework.mail.MailSendException sendException
                    && sendException.getFailedMessages() != null) {
                sendException.getFailedMessages().values().stream()
                        .filter(java.util.Objects::nonNull).forEach(pending::push);
            }
        }
        return causes;
    }

    public record Classification(boolean retryable, String reasonCode) {
    }
}
