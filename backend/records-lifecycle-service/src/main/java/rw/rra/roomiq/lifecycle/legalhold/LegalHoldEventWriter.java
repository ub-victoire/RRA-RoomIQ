package rw.rra.roomiq.lifecycle.legalhold;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.TreeSet;

@Component
public class LegalHoldEventWriter {
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    @Autowired
    public LegalHoldEventWriter(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this(jdbcTemplate, objectMapper, Clock.systemUTC());
    }

    LegalHoldEventWriter(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper, Clock clock) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    public void lockAggregate(UUID holdId) {
        lockKey("legal-hold:" + holdId);
    }

    public void lockScopes(LegalHoldScope... scopes) {
        TreeSet<String> keys = new TreeSet<>();
        for (LegalHoldScope scope : scopes) {
            if (scope == null) {
                continue;
            }
            if (scope.type() == LegalHoldScopeType.CATEGORY) {
                keys.add("hold-category:" + scope.categoryCode());
            } else {
                keys.add("hold-record:" + scope.sourceService() + ":" + scope.recordType() + ":" + scope.recordId());
            }
        }
        keys.forEach(this::lockKey);
    }

    public void lockEvaluationScope(String sourceService, String recordType, String recordId, String categoryCode) {
        TreeSet<String> keys = new TreeSet<>(List.of(
                "hold-record:" + sourceService + ":" + recordType + ":" + recordId));
        if (categoryCode != null) {
            keys.add("hold-category:" + categoryCode);
        }
        keys.forEach(this::lockKey);
    }

    private void lockKey(String key) {
        jdbcTemplate.execute((org.springframework.jdbc.core.ConnectionCallback<Void>) connection -> {
            try (var statement = connection.prepareStatement(
                    "SELECT pg_advisory_xact_lock(hashtextextended(?, 718239))")) {
                statement.setString(1, key);
                statement.execute();
            }
            return null;
        });
    }

    public UUID eventId(UUID actor, String operation, String idempotencyKey, UUID holdId) {
        return UUID.nameUUIDFromBytes((actor + "\n" + operation + "\n" + idempotencyKey + "\n" + holdId)
                .getBytes(StandardCharsets.UTF_8));
    }

    public boolean alreadyApplied(UUID eventId, UUID holdId, String requestHash) {
        String metadata = jdbcTemplate.query("""
                SELECT metadata::text FROM lifecycle_control_event WHERE event_id = ?
                """, resultSet -> resultSet.next() ? resultSet.getString(1) : null, eventId);
        if (metadata == null) {
            return false;
        }
        JsonNode node = objectMapper.readTree(metadata);
        if (!holdId.toString().equals(node.path("resourceId").asString())
                || !requestHash.equals(node.path("requestHash").asString())) {
            throw conflict("IDEMPOTENCY_KEY_REUSED", "Idempotency key was already used for a different request");
        }
        return true;
    }

    public UUID append(UUID actor, UUID holdId, UUID eventId, String operation, String requestHash,
                       String idempotencyKey, String correlationId, LegalHoldRecord before,
                       LegalHoldRecord after) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("schemaVersion", 1);
        metadata.put("operation", operation);
        metadata.put("outcome", "SUCCEEDED");
        metadata.put("resourceType", "LEGAL_HOLD");
        metadata.put("resourceId", holdId.toString());
        metadata.put("version", after.version());
        metadata.put("requestHash", requestHash);
        metadata.put("requestKeyHash", sha256(idempotencyKey));
        metadata.put("correlationId", correlationId);
        metadata.put("before", before);
        metadata.put("after", after);

        try {
            String json = objectMapper.writeValueAsString(metadata);
            String afterJson = objectMapper.writeValueAsString(after);
            lockAggregate(holdId);
            Long current = jdbcTemplate.queryForObject("""
                    SELECT COALESCE(MAX(sequence_no), 0)
                    FROM lifecycle_control_event
                    WHERE aggregate_type = 'HOLD' AND aggregate_id = ?
                    """, Long.class, holdId);
            long sequence = (current == null ? 0 : current) + 1;
            jdbcTemplate.update("""
                    INSERT INTO lifecycle_control_event
                        (event_id, aggregate_type, aggregate_id, sequence_no, event_type, actor_subject,
                         recorded_at, evidence_sha256, metadata)
                    VALUES (?, 'HOLD', ?, ?, ?, ?, ?, ?, CAST(? AS JSONB))
                    """, eventId, holdId, sequence, operation, actor.toString(),
                    Timestamp.from(clock.instant()), sha256(afterJson), json);
            jdbcTemplate.queryForObject(
                    "SELECT set_config('roomiq.lifecycle.event_id', ?, TRUE)", String.class, eventId.toString());
            return eventId;
        } catch (JacksonException exception) {
            throw new IllegalStateException("Legal hold lifecycle evidence could not be serialized", exception);
        }
    }

    public String digest(Object value) {
        try {
            return sha256(objectMapper.writeValueAsString(value));
        } catch (JacksonException exception) {
            throw new IllegalStateException("Legal hold request could not be serialized", exception);
        }
    }

    private static String sha256(String value) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static rw.rra.roomiq.common.web.DomainException conflict(String code, String message) {
        return new rw.rra.roomiq.common.web.DomainException(
                org.springframework.http.HttpStatus.CONFLICT, code, message);
    }
}
