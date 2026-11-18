package rw.rra.roomiq.audit.integration;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.InputStream;

import static org.assertj.core.api.Assertions.assertThat;

class AuditSourceEvidenceContractFixtureTests {
    @Test
    void auditEvidenceDtoRoundTripsTheSharedWireFixture() throws Exception {
        JsonMapper mapper = JsonMapper.builder().findAndAddModules().build();
        try (InputStream input = getClass().getResourceAsStream("/contract-fixtures/audit-rlc-v1.json")) {
            assertThat(input).isNotNull();
            JsonNode fixture = mapper.readTree(input);
            JsonNode expected = fixture.get("evidence");
            var evidence = mapper.treeToValue(expected, AuditSourceEvidenceDeliveryClient.SourceOwnerEvidence.class);
            assertThat(mapper.readTree(mapper.writeValueAsString(evidence))).isEqualTo(expected);
        }
    }
}
