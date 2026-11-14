package rw.rra.roomiq.lifecycle.holdcontrol;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import rw.rra.roomiq.common.web.DomainException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@Component
public class RecordsHoldEventWriter {
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public RecordsHoldEventWriter(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    public String digest(Object value) {
        try {
            return sha256(objectMapper.writeValueAsString(value));
        } catch (JacksonException exception) {
            throw new IllegalStateException("Legal-hold request could not be fingerprinted", exception);
        }
    }

    public boolean exists(UUID actorId, String eventType, String idempotencyKey,
                          String requestHash, UUID holdId) {
        String metadata = jdbcTemplate.query(
                "SELECT metadata::text FROM lifecycle_control_event WHERE event_id = ?",
                resultSet -> resultSet.next() ? resultSet.getString(1) : null,
                eventId(actorId, eventType, idempotencyKey));
        if (metadata == null) {
            return false;
        }
        JsonNode node = objectMapper.readTree(metadata);
        if (!requestHash.equals(node.path("requestHash").asString())
                || !holdId.toString().equals(node.path("resourceId").asString())) {
            throw conflict("IDEMPOTENCY_KEY_REUSED", "Idempotency key was already used for a different request");
        }
        return true;
    }

    public boolean append(UUID actorId, String eventType, String idempotencyKey, String requestHash,
                          UUID holdId, String beforeStatus, String afterStatus, String reference,
                          String correlationId, Instant recordedAt) {
        return append(eventId(actorId, eventType, idempotencyKey), actorId.toString(), eventType,
                idempotencyKey, requestHash, holdId, beforeStatus, afterStatus, reference,
                correlationId, recordedAt);
    }

    public boolean appendService(String serviceSubject, String eventType, String idempotencyKey,
                                 String requestHash, UUID holdId, String beforeStatus, String afterStatus,
                                 String reference, String correlationId, Instant recordedAt) {
        UUID eventId = eventId(serviceSubject, eventType, idempotencyKey);
        return append(eventId, serviceSubject, eventType, idempotencyKey, requestHash, holdId,
                beforeStatus, afterStatus, reference, correlationId, recordedAt);
    }

    private boolean append(UUID eventId, String actorSubject, String eventType, String idempotencyKey,
                           String requestHash, UUID holdId, String beforeStatus, String afterStatus,
                           String reference, String correlationId, Instant recordedAt) {
        lockEvent(eventId);
        if (exists(eventId, requestHash, holdId)) {
            setEventId(eventId);
            return false;
        }
        lockAggregate(holdId);
        if (exists(eventId, requestHash, holdId)) {
            setEventId(eventId);
            return false;
        }

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("schemaVersion", 1);
        metadata.put("operation", eventType);
        metadata.put("requestHash", requestHash);
        metadata.put("requestKeyHash", sha256(idempotencyKey));
        metadata.put("resourceId", holdId.toString());
        if (beforeStatus != null) {
            metadata.put("beforeStatus", beforeStatus);
        }
        metadata.put("afterStatus", afterStatus);
        if (reference != null) {
            metadata.put("referenceSha256", sha256(reference));
        }
        if (correlationId != null && !correlationId.isBlank()) {
            metadata.put("correlationId", correlationId);
        }

        try {
            String json = objectMapper.writeValueAsString(metadata);
            long sequence = nextSequence(holdId);
            jdbcTemplate.update("""
                    INSERT INTO lifecycle_control_event (
                        event_id, aggregate_type, aggregate_id, sequence_no, event_type,
                        actor_subject, recorded_at, evidence_sha256, metadata)
                    VALUES (?, 'HOLD', ?, ?, ?, ?, ?, ?, ?::jsonb)
                    """, eventId, holdId, sequence, eventType, actorSubject,
                    Timestamp.from(recordedAt), sha256(json), json);
            setEventId(eventId);
            return true;
        } catch (JacksonException exception) {
            throw new IllegalStateException("Legal-hold event metadata could not be serialized", exception);
        }
    }

    private boolean exists(UUID eventId, String requestHash, UUID holdId) {
        String metadata = jdbcTemplate.query(
                "SELECT metadata::text FROM lifecycle_control_event WHERE event_id = ?",
                resultSet -> resultSet.next() ? resultSet.getString(1) : null, eventId);
        if (metadata == null) {
            return false;
        }
        JsonNode node = objectMapper.readTree(metadata);
        if (!requestHash.equals(node.path("requestHash").asString())
                || !holdId.toString().equals(node.path("resourceId").asString())) {
            throw conflict("IDEMPOTENCY_KEY_REUSED", "Hold acknowledgement identity was reused");
        }
        return true;
    }

    private long nextSequence(UUID holdId) {
        Long current = jdbcTemplate.queryForObject("""
                SELECT COALESCE(MAX(sequence_no), 0)
                FROM lifecycle_control_event
                WHERE aggregate_type = 'HOLD' AND aggregate_id = ?
                """, Long.class, holdId);
        return (current == null ? 0 : current) + 1;
    }

    private void lockAggregate(UUID holdId) {
        jdbcTemplate.execute((org.springframework.jdbc.core.ConnectionCallback<Void>) connection -> {
            try (var statement = connection.prepareStatement(
                    "SELECT pg_advisory_xact_lock(hashtextextended(?, 718239))")) {
                statement.setString(1, "legal-hold:" + holdId);
                statement.execute();
            }
            return null;
        });
    }

    private void lockEvent(UUID eventId) {
        jdbcTemplate.execute((org.springframework.jdbc.core.ConnectionCallback<Void>) connection -> {
            try (var statement = connection.prepareStatement(
                    "SELECT pg_advisory_xact_lock(hashtextextended(?, 718240))")) {
                statement.setString(1, eventId.toString());
                statement.execute();
            }
            return null;
        });
    }

    private void setEventId(UUID eventId) {
        jdbcTemplate.queryForObject(
                "SELECT set_config('roomiq.lifecycle.hold_event_id', ?, TRUE)",
                String.class, eventId.toString());
    }

    private static UUID eventId(UUID actorId, String eventType, String idempotencyKey) {
        return eventId(actorId.toString(), eventType, idempotencyKey);
    }

    private static UUID eventId(String subject, String eventType, String idempotencyKey) {
        ByteBuffer bytes = ByteBuffer.wrap(sha256Bytes(subject + "\n" + eventType + "\n" + idempotencyKey));
        long most = bytes.getLong();
        long least = bytes.getLong();
        most = (most & 0xFFFFFFFFFFFF0FFFL) | 0x0000000000008000L;
        least = (least & 0x3FFFFFFFFFFFFFFFL) | 0x8000000000000000L;
        return new UUID(most, least);
    }

    private static String sha256(String value) {
        return java.util.HexFormat.of().formatHex(sha256Bytes(value));
    }

    private static byte[] sha256Bytes(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static DomainException conflict(String code, String message) {
        return new DomainException(org.springframework.http.HttpStatus.CONFLICT, code, message);
    }
}
