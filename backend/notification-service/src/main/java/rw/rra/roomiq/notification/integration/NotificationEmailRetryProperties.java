package rw.rra.roomiq.notification.integration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class NotificationEmailRetryProperties {
    private final int maximumAttempts;
    private final long initialDelayMs;
    private final double multiplier;
    private final long maximumDelayMs;
    private final String retryableSmtpResponseCodes;

    public NotificationEmailRetryProperties(
            @Value("${roomiq.notification.email.retry.maximum-attempts:5}") int maximumAttempts,
            @Value("${roomiq.notification.email.retry.initial-delay-ms:60000}") long initialDelayMs,
            @Value("${roomiq.notification.email.retry.multiplier:2}") double multiplier,
            @Value("${roomiq.notification.email.retry.maximum-delay-ms:480000}") long maximumDelayMs,
            @Value("${roomiq.notification.email.retry.retryable-smtp-response-codes:}")
            String retryableSmtpResponseCodes) {
        if (maximumAttempts < 1 || maximumAttempts > 50
                || initialDelayMs < 1 || maximumDelayMs < initialDelayMs
                || !Double.isFinite(multiplier) || multiplier < 1.0) {
            throw new IllegalArgumentException("Notification email retry policy is invalid");
        }
        this.maximumAttempts = maximumAttempts;
        this.initialDelayMs = initialDelayMs;
        this.multiplier = multiplier;
        this.maximumDelayMs = maximumDelayMs;
        this.retryableSmtpResponseCodes = retryableSmtpResponseCodes == null
                ? "" : retryableSmtpResponseCodes;
        validateResponseCodes(this.retryableSmtpResponseCodes);
    }

    public boolean hasRetryRemaining(int attemptNo) {
        return attemptNo < maximumAttempts;
    }

    public long delayAfterAttemptMs(int attemptNo) {
        if (attemptNo < 1) {
            throw new IllegalArgumentException("Attempt number must be positive");
        }
        double delay = initialDelayMs * Math.pow(multiplier, attemptNo - 1);
        return delay >= maximumDelayMs ? maximumDelayMs : (long) delay;
    }

    public boolean isRetryableSmtpResponseCode(int responseCode) {
        if (responseCode >= 400 && responseCode <= 499) {
            return true;
        }
        if (responseCode < 500 || responseCode > 599) {
            return false;
        }
        for (String configuredCode : retryableSmtpResponseCodes.split(",")) {
            if (configuredCode.strip().equals(Integer.toString(responseCode))) {
                return true;
            }
        }
        return false;
    }

    public int maximumAttempts() { return maximumAttempts; }

    private static void validateResponseCodes(String value) {
        if (value.isBlank()) {
            return;
        }
        for (String code : value.split(",", -1)) {
            String trimmed = code.strip();
            if (!trimmed.matches("5[0-9]{2}")) {
                throw new IllegalArgumentException(
                        "Retryable provider-specific SMTP response codes must be 5xx codes");
            }
        }
    }
}
