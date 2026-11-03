package rw.rra.roomiq.audit.domain;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IdentityAuditEventCodecTests {
    private final IdentityAuditEventCodec codec = new IdentityAuditEventCodec(new ObjectMapper());

    @Test
    void acceptsUnauthenticatedFailureWithoutFabricatingAnActor() {
        UUID eventId = UUID.randomUUID();
        var event = codec.decode(eventJson(eventId, "AUTHENTICATION_FAILED", "AUTHENTICATION",
                "DENIED", "null", "null", "null", "{}", "identity-service", 1)
                .getBytes(StandardCharsets.UTF_8));

        assertThat(event.eventId()).isEqualTo(eventId);
        assertThat(event.actorUserId()).isNull();
        assertThat(event.resourceId()).isNull();
        assertThat(event.scopeOfficeBuildingId()).isNull();
        assertThat(event.outcome()).isEqualTo("DENIED");
        assertThat(event.metadata()).contains("\"sourceService\":\"identity-service\"")
                .contains("\"schemaVersion\":1");
    }

    @Test
    void acceptsOnlyAllowlistedPermissionAndPrivilegeMetadata() throws Exception {
        UUID eventId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();
        UUID permissionId = UUID.randomUUID();
        var event = codec.decode(eventJson(eventId, "IDENTITY_ROLE_PERMISSION_REVOKED", "ROLE",
                "SUCCESS", quote(actorId.toString()), quote(UUID.randomUUID().toString()), "null",
                "{\"permissionId\":\"" + permissionId + "\"}", "identity-service", 1)
                .getBytes(StandardCharsets.UTF_8));
        JsonNode stored = new ObjectMapper().readTree(event.metadata());

        assertThat(stored.path("eventMetadata").path("permissionId").asText()).isEqualTo(permissionId.toString());
    }

    @Test
    void rejectsUnsupportedSourceTypeVersionResourceOutcomeAndSensitiveMetadata() {
        UUID eventId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();
        UUID resourceId = UUID.randomUUID();
        String base = eventJson(eventId, "IDENTITY_USER_STATUS_CHANGED", "USER",
                "SUCCESS", quote(actorId.toString()), quote(resourceId.toString()), "null",
                "{}", "identity-service", 1);
        assertInvalid(base.replace("identity-service", "booking-service"));
        assertInvalid(base.replace("IDENTITY_USER_STATUS_CHANGED", "UNAPPROVED_EVENT"));
        assertInvalid(base.replace("\"schemaVersion\":1", "\"schemaVersion\":2"));
        assertInvalid(base.replace("\"resourceType\":\"USER\"", "\"resourceType\":\"ROLE\""));
        assertInvalid(base.replace("\"outcome\":\"SUCCESS\"", "\"outcome\":\"DENIED\""));
        assertInvalid(base.replace("\"metadata\":{}", "\"metadata\":{\"password\":\"not-allowed\"}"));
        assertInvalid(base.replace("\"actorUserId\":\"" + actorId + "\"", "\"actorUserId\":null"));
    }

    @Test
    void exposesTheCompleteExistingIdentityV1EventSet() {
        assertThat(IdentityAuditEventCodec.supportedEventTypes()).containsExactlyInAnyOrder(
                "AUTHENTICATION_SUCCEEDED", "AUTHENTICATION_FAILED", "REFRESH_TOKEN_ROTATED",
                "REFRESH_TOKEN_REJECTED", "AUTHENTICATION_LOGOUT", "AUTHORIZATION_DENIED",
                "IDENTITY_USER_CREATED", "IDENTITY_USER_UPDATED", "IDENTITY_USER_STATUS_CHANGED",
                "IDENTITY_USER_SOFT_DELETED", "IDENTITY_ROLE_CREATED", "IDENTITY_PERMISSION_CREATED",
                "IDENTITY_ROLE_PERMISSION_GRANTED", "IDENTITY_ROLE_PERMISSION_REVOKED",
                "IDENTITY_USER_ROLE_ASSIGNED", "IDENTITY_USER_ROLE_REVOKED",
                "IDENTITY_PRIVILEGE_GRANTED", "IDENTITY_PRIVILEGE_REVOKED");
    }

    private void assertInvalid(String payload) {
        assertThatThrownBy(() -> codec.decode(payload.getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static String eventJson(UUID eventId, String eventType, String resourceType, String outcome,
                                    String actorId, String resourceId, String scopeId, String metadata,
                                    String source, int version) {
        return """
                {
                  "eventId":"%s",
                  "schemaVersion":%d,
                  "eventType":"%s",
                  "timestamp":"2026-11-03T07:00:00Z",
                  "sourceService":"%s",
                  "actorUserId":%s,
                  "resourceType":"%s",
                  "resourceId":%s,
                  "scopeOfficeBuildingId":%s,
                  "outcome":"%s",
                  "correlationId":"correlation-1",
                  "metadata":%s
                }
                """.formatted(eventId, version, eventType, source, actorId, resourceType, resourceId,
                scopeId, outcome, metadata);
    }

    private static String quote(String value) {
        return "\"" + value + "\"";
    }
}
