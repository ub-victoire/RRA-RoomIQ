package rw.rra.roomiq.lifecycle.policy;

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
import java.time.Instant;
import java.sql.Timestamp;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@Component
public class LifecycleControlEventWriter {
    private static final long UUID_VERSION_MASK = 0xFFFFFFFFFFFF0FFFL;
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public LifecycleControlEventWriter(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    public boolean exists(UUID eventId, String requestHash, UUID resourceId) {
        String metadata = jdbcTemplate.query("SELECT metadata::text FROM lifecycle_control_event WHERE event_id = ?",
                resultSet -> resultSet.next() ? resultSet.getString(1) : null, eventId);
        if (metadata == null) {
            return false;
        }
        JsonNode node = objectMapper.readTree(metadata);
        if (!requestHash.equals(node.path("requestHash").asString())
                || !resourceId.toString().equals(node.path("resourceId").asString())) {
            throw conflict("IDEMPOTENCY_KEY_REUSED", "Idempotency key was already used for a different request");
        }
        return true;
    }

    public UUID append(UUID actorId, String operation, String idempotencyKey, String requestHash,
                       UUID policyId, UUID resourceId, String resourceType, String outcome,
                       String beforeDigest, String afterDigest, String correlationId,
                       Map<String, Object> additionalMetadata) {
        UUID eventId = eventId(actorId, operation, idempotencyKey, resourceId);
        if (exists(eventId, requestHash, resourceId)) {
            return eventId;
        }

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("schemaVersion", 1);
        metadata.put("operation", operation);
        metadata.put("outcome", outcome);
        metadata.put("resourceType", resourceType);
        metadata.put("resourceId", resourceId.toString());
        metadata.put("requestHash", requestHash);
        metadata.put("requestKeyHash", sha256(idempotencyKey));
        if (beforeDigest != null) {
            metadata.put("beforeDigest", beforeDigest);
        }
        if (afterDigest != null) {
            metadata.put("afterDigest", afterDigest);
        }
        if (correlationId != null && !correlationId.isBlank()) {
            metadata.put("correlationId", correlationId);
        }
        if (additionalMetadata != null) {
            for (Map.Entry<String, Object> entry : additionalMetadata.entrySet()) {
                if (!ALLOWED_ADDITIONAL_METADATA.contains(entry.getKey())) {
                    throw new IllegalArgumentException("Lifecycle event metadata key is not allowed");
                }
                metadata.put(entry.getKey(), entry.getValue());
            }
        }

        try {
            String json = objectMapper.writeValueAsString(metadata);
            long sequence = nextSequence(policyId);
            jdbcTemplate.update("""
                    INSERT INTO lifecycle_control_event
                        (event_id, aggregate_type, aggregate_id, sequence_no, event_type, actor_subject,
                         recorded_at, evidence_sha256, metadata)
                    VALUES (?, 'POLICY', ?, ?, ?, ?, ?, ?, ?::jsonb)
                    ON CONFLICT (event_id) DO NOTHING
                    """, eventId, policyId, sequence, operation, actorId.toString(), Timestamp.from(Instant.now()),
                    afterDigest, json);
            if (!exists(eventId, requestHash, resourceId)) {
                throw conflict("IDEMPOTENCY_CONFLICT", "The lifecycle event could not be recorded uniquely");
            }
            jdbcTemplate.queryForObject(
                    "SELECT set_config('roomiq.lifecycle.event_id', ?, TRUE)", String.class, eventId.toString());
            return eventId;
        } catch (JacksonException exception) {
            throw new IllegalStateException("Lifecycle event metadata could not be serialized", exception);
        }
    }

    public long nextSequence(UUID policyId) {
        Long current = jdbcTemplate.queryForObject("""
                SELECT COALESCE(MAX(sequence_no), 0)
                FROM lifecycle_control_event
                WHERE aggregate_type = 'POLICY' AND aggregate_id = ?
                """, Long.class, policyId);
        return (current == null ? 0 : current) + 1;
    }

    public void lockNewAggregate(UUID policyId) {
        jdbcTemplate.execute((org.springframework.jdbc.core.ConnectionCallback<Void>) connection -> {
            try (var statement = connection.prepareStatement(
                    "SELECT pg_advisory_xact_lock(hashtextextended(?, 718239))")) {
                statement.setString(1, policyId.toString());
                statement.execute();
            }
            return null;
        });
    }

    public UUID eventId(UUID actorId, String operation, String idempotencyKey, UUID resourceId) {
        return uuidV8(sha256Bytes(actorId + "\n" + operation + "\n" + idempotencyKey + "\n" + resourceId));
    }

    public String digest(Object value) {
        try {
            return sha256(objectMapper.writeValueAsString(value));
        } catch (JacksonException exception) {
            throw new IllegalStateException("Lifecycle evidence could not be serialized", exception);
        }
    }

    private static UUID uuidV8(byte[] digest) {
        ByteBuffer bytes = ByteBuffer.wrap(digest);
        long most = bytes.getLong();
        long least = bytes.getLong();
        most = (most & UUID_VERSION_MASK) | 0x0000000000008000L;
        least = (least & 0x3FFFFFFFFFFFFFFFL) | 0x8000000000000000L;
        return new UUID(most, least);
    }

    private static String sha256(String value) {
        byte[] digest = sha256Bytes(value);
        return java.util.HexFormat.of().formatHex(digest);
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

    private static final java.util.Set<String> ALLOWED_ADDITIONAL_METADATA = java.util.Set.of(
            "ruleId", "validationCodes", "effectiveFrom", "effectiveTo", "retroactiveEvidenceRef");
}
