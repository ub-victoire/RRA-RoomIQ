package rw.rra.roomiq.lifecycle.policy;

import org.springframework.http.HttpStatus;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import rw.rra.roomiq.common.web.DomainException;
import rw.rra.roomiq.lifecycle.policy.api.ActivatePolicyRequest;
import rw.rra.roomiq.lifecycle.policy.api.CreatePolicyRequest;
import rw.rra.roomiq.lifecycle.policy.api.PolicyResponse;
import rw.rra.roomiq.lifecycle.policy.api.PolicyRuleRequest;
import rw.rra.roomiq.lifecycle.policy.api.PolicyRuleResponse;
import rw.rra.roomiq.lifecycle.policy.api.PolicyValidationResponse;
import rw.rra.roomiq.lifecycle.policy.api.SupersedePolicyRequest;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class PolicyLifecycleService {
    private final LifecyclePolicyVersionRepository versionRepository;
    private final LifecyclePolicyRuleRepository ruleRepository;
    private final PolicyDefinitionValidator validator;
    private final LifecycleControlEventWriter eventWriter;
    private final JdbcTemplate jdbcTemplate;
    private final Clock clock;

    @Autowired
    public PolicyLifecycleService(LifecyclePolicyVersionRepository versionRepository,
                                  LifecyclePolicyRuleRepository ruleRepository,
                                  PolicyDefinitionValidator validator,
                                  LifecycleControlEventWriter eventWriter,
                                  JdbcTemplate jdbcTemplate) {
        this(versionRepository, ruleRepository, validator, eventWriter, jdbcTemplate, Clock.systemUTC());
    }
    PolicyLifecycleService(LifecyclePolicyVersionRepository versionRepository,
                           LifecyclePolicyRuleRepository ruleRepository,
                           PolicyDefinitionValidator validator,
                           LifecycleControlEventWriter eventWriter,
                           JdbcTemplate jdbcTemplate, Clock clock) {
        this.versionRepository = versionRepository;
        this.ruleRepository = ruleRepository;
        this.validator = validator;
        this.eventWriter = eventWriter;
        this.jdbcTemplate = jdbcTemplate;
        this.clock = clock;
    }

    @Transactional
    public PolicyResponse create(CreatePolicyRequest request, UUID actorId, String idempotencyKey,
                                 String correlationId) {
        UUID policyId = request.policyVersionId();
        String requestHash = eventWriter.digest(request);
        UUID eventId = eventWriter.eventId(actorId, "POLICY_CREATED", idempotencyKey, policyId);
        if (eventWriter.exists(eventId, requestHash, policyId)) {
            return response(requirePolicy(policyId));
        }

        lockNewAggregate(policyId);
        if (eventWriter.exists(eventId, requestHash, policyId)) {
            return response(requirePolicy(policyId));
        }
        if (versionRepository.existsById(policyId)) {
            throw conflict("POLICY_VERSION_EXISTS", "Policy version identifier already exists");
        }

        Instant now = clock.instant();
        PolicyVersionDefinition definition = new PolicyVersionDefinition(policyId, request.versionLabel(),
                request.documentSha256(), null, null, null, null, null, null, null, null, null,
                PolicyStatus.DRAFT, now);
        LifecyclePolicyVersion version = new LifecyclePolicyVersion(definition, validator);
        String afterDigest = policyDigest(version, request.rules());
        eventWriter.append(actorId, "POLICY_CREATED", idempotencyKey, requestHash, policyId, policyId,
                "POLICY_VERSION", "SUCCEEDED", null, afterDigest, correlationId, Map.of());
        versionRepository.saveAndFlush(version);

        for (PolicyRuleRequest ruleRequest : request.rules()) {
            PolicyRuleDefinition ruleDefinition = ruleDefinition(policyId, ruleRequest, now);
            validator.validateRule(ruleDefinition);
            String ruleHash = eventWriter.digest(ruleState(ruleDefinition));
            eventWriter.append(actorId, "POLICY_RULE_CREATED", idempotencyKey, requestHash, policyId,
                    ruleRequest.ruleId(), "POLICY_RULE", "SUCCEEDED", null, ruleHash, correlationId,
                    Map.of("ruleId", ruleRequest.ruleId().toString()));
            ruleRepository.saveAndFlush(new LifecyclePolicyRule(ruleDefinition, version, validator));
        }
        return response(version);
    }

    @Transactional(readOnly = true)
    public PolicyResponse get(UUID policyId) {
        return response(requirePolicy(policyId));
    }

    @Transactional
    public PolicyRuleResponse createRule(UUID policyId, PolicyRuleRequest request, UUID actorId,
                                         String idempotencyKey, String correlationId) {
        String requestHash = eventWriter.digest(Map.of("policyVersionId", policyId, "request", request));
        UUID eventId = eventWriter.eventId(actorId, "POLICY_RULE_CREATED", idempotencyKey, request.ruleId());
        if (eventWriter.exists(eventId, requestHash, request.ruleId())) {
            LifecyclePolicyRule replayed = requireRule(request.ruleId());
            if (!policyId.equals(replayed.getPolicyVersionId())) {
                throw conflict("IDEMPOTENCY_KEY_REUSED", "Idempotency key refers to another policy");
            }
            return ruleResponse(replayed);
        }

        LifecyclePolicyVersion version = lockPolicy(policyId);
        if (eventWriter.exists(eventId, requestHash, request.ruleId())) {
            LifecyclePolicyRule replayed = requireRule(request.ruleId());
            if (!policyId.equals(replayed.getPolicyVersionId())) {
                throw conflict("IDEMPOTENCY_KEY_REUSED", "Idempotency key refers to another policy");
            }
            return ruleResponse(replayed);
        }
        requireDraft(version);
        if (ruleRepository.existsById(request.ruleId())) {
            throw conflict("POLICY_RULE_EXISTS", "Policy rule identifier already exists");
        }
        PolicyRuleDefinition definition = ruleDefinition(policyId, request, clock.instant());
        validator.validateRule(definition);
        String afterDigest = eventWriter.digest(ruleState(definition));
        eventWriter.append(actorId, "POLICY_RULE_CREATED", idempotencyKey, requestHash,
                policyId, request.ruleId(), "POLICY_RULE", "SUCCEEDED", null, afterDigest, correlationId,
                Map.of("ruleId", request.ruleId().toString()));
        LifecyclePolicyRule rule = ruleRepository.saveAndFlush(new LifecyclePolicyRule(definition, version, validator));
        return ruleResponse(rule);
    }

    @Transactional
    public PolicyRuleResponse updateRule(UUID policyId, UUID ruleId, PolicyRuleRequest request, UUID actorId,
                                         String idempotencyKey, String correlationId) {
        if (!ruleId.equals(request.ruleId())) {
            throw badRequest("POLICY_RULE_ID_MISMATCH", "Path and request rule identifiers must match");
        }
        String requestHash = eventWriter.digest(Map.of("policyVersionId", policyId, "ruleId", ruleId,
                "request", request));
        String operation = "POLICY_RULE_MODIFIED";
        UUID eventId = eventWriter.eventId(actorId, operation, idempotencyKey, ruleId);
        if (eventWriter.exists(eventId, requestHash, ruleId)) {
            LifecyclePolicyRule replayed = requireRule(ruleId);
            if (!policyId.equals(replayed.getPolicyVersionId())) {
                throw conflict("IDEMPOTENCY_KEY_REUSED", "Idempotency key refers to another policy");
            }
            return ruleResponse(replayed);
        }

        LifecyclePolicyVersion version = lockPolicy(policyId);
        if (eventWriter.exists(eventId, requestHash, ruleId)) {
            LifecyclePolicyRule replayed = requireRule(ruleId);
            if (!policyId.equals(replayed.getPolicyVersionId())) {
                throw conflict("IDEMPOTENCY_KEY_REUSED", "Idempotency key refers to another policy");
            }
            return ruleResponse(replayed);
        }
        requireDraft(version);
        LifecyclePolicyRule rule = ruleRepository.findByIdForUpdate(ruleId)
                .orElseThrow(() -> notFound("POLICY_RULE_NOT_FOUND", "Policy rule was not found"));
        if (!policyId.equals(rule.getPolicyVersionId())) {
            throw notFound("POLICY_RULE_NOT_FOUND", "Policy rule was not found");
        }
        String beforeDigest = eventWriter.digest(ruleState(rule));
        PolicyRuleDefinition definition = ruleDefinition(policyId, request, rule.getCreatedAt());
        validator.validateRule(definition);
        String afterDigest = eventWriter.digest(ruleState(definition));
        eventWriter.append(actorId, operation, idempotencyKey, requestHash, policyId, ruleId,
                "POLICY_RULE", "SUCCEEDED", beforeDigest, afterDigest, correlationId,
                Map.of("ruleId", ruleId.toString()));
        rule.update(definition, validator);
        return ruleResponse(ruleRepository.saveAndFlush(rule));
    }

    @Transactional
    public PolicyValidationResponse validate(UUID policyId, UUID actorId, String idempotencyKey,
                                             String correlationId) {
        String requestHash = eventWriter.digest(Map.of("policyVersionId", policyId, "operation", "VALIDATE"));
        UUID eventId = eventWriter.eventId(actorId, "POLICY_VALIDATED", idempotencyKey, policyId);
        if (eventWriter.exists(eventId, requestHash, policyId)) {
            return validationResponse(policyId, eventMetadata(eventId));
        }

        LifecyclePolicyVersion version = lockPolicy(policyId);
        if (eventWriter.exists(eventId, requestHash, policyId)) {
            return validationResponse(policyId, eventMetadata(eventId));
        }
        List<String> errors = validationErrors(version);
        String outcome = errors.isEmpty() ? "SUCCEEDED" : "REJECTED";
        String digest = policyDigest(version, ruleRepository.findByPolicyVersion_PolicyVersionIdOrderByRuleCode(policyId));
        eventWriter.append(actorId, "POLICY_VALIDATED", idempotencyKey, requestHash, policyId, policyId,
                "POLICY_VERSION", outcome, digest, digest, correlationId,
                Map.of("validationCodes", errors.isEmpty() ? List.of("VALID") : errors));
        return new PolicyValidationResponse(policyId, errors.isEmpty(), errors);
    }

    @Transactional
    public PolicyResponse activate(UUID policyId, ActivatePolicyRequest request, UUID actorId,
                                   String idempotencyKey, String correlationId) {
        String requestHash = eventWriter.digest(request);
        UUID eventId = eventWriter.eventId(actorId, "POLICY_ACTIVATED", idempotencyKey, policyId);
        if (eventWriter.exists(eventId, requestHash, policyId)) {
            return response(requirePolicy(policyId));
        }

        lockActivationSet();
        LifecyclePolicyVersion version = lockPolicy(policyId);
        if (eventWriter.exists(eventId, requestHash, policyId)) {
            return response(version);
        }
        requireDraft(version);
        Instant now = clock.instant();
        if (request.effectiveFrom().isBefore(now)) {
            throw conflict("RETROACTIVE_ACTIVATION_UNSUPPORTED",
                    "Retroactive activation requires an approved recalculation workflow");
        }
        List<String> errors = validationErrors(version);
        if (!errors.isEmpty()) {
            throw badRequest("POLICY_VALIDATION_FAILED", "Policy must pass validation before activation");
        }
        if (ruleRepository.findByPolicyVersion_PolicyVersionIdOrderByRuleCode(policyId).stream()
                .anyMatch(rule -> "RRA_PROPOSED".equals(rule.getRuleSource()))) {
            throw badRequest("POLICY_RULE_NOT_APPROVED",
                    "Every rule must use an approved RRA or statutory source before activation");
        }
        List<LifecyclePolicyVersion> overlaps = request.effectiveTo() == null
                ? versionRepository.findOverlappingWithoutEnd(request.effectiveFrom())
                : versionRepository.findOverlappingWithEnd(request.effectiveFrom(), request.effectiveTo());
        if (!overlaps.isEmpty()) {
            throw conflict("POLICY_EFFECTIVE_DATE_OVERLAP",
                    "Overlapping approved policies must be explicitly superseded before activation");
        }

        PolicyVersionDefinition approvedDefinition = new PolicyVersionDefinition(
                version.getPolicyVersionId(), version.getVersionLabel(), version.getDocumentSha256(),
                request.approvalReference(), request.policyVersion(), request.effectiveFrom(), request.effectiveTo(),
                request.approvingAuthorityRef(), actorId.toString(), now, request.legalValidationRef(),
                request.recordsValidationRef(), PolicyStatus.APPROVED, version.getCreatedAt());
        validator.validateVersion(approvedDefinition);
        String beforeDigest = policyDigest(version,
                ruleRepository.findByPolicyVersion_PolicyVersionIdOrderByRuleCode(policyId));
        String afterDigest = activatedPolicyDigest(version, approvedDefinition);

        UUID validationEvent = eventWriter.eventId(actorId, "POLICY_VALIDATED", idempotencyKey, policyId);
        if (!eventWriter.exists(validationEvent,
                eventWriter.digest(Map.of("policyVersionId", policyId, "operation", "VALIDATE")), policyId)) {
            eventWriter.append(actorId, "POLICY_VALIDATED", idempotencyKey,
                    eventWriter.digest(Map.of("policyVersionId", policyId, "operation", "VALIDATE")),
                    policyId, policyId, "POLICY_VERSION", "SUCCEEDED", beforeDigest, beforeDigest,
                    correlationId, Map.of("validationCodes", List.of("VALID")));
        }
        eventWriter.append(actorId, "POLICY_ACTIVATED", idempotencyKey, requestHash, policyId, policyId,
                "POLICY_VERSION", "SUCCEEDED", beforeDigest, afterDigest, correlationId,
                Map.of("effectiveFrom", request.effectiveFrom().toString(),
                        "effectiveTo", request.effectiveTo() == null ? "" : request.effectiveTo().toString()));
        version.activate(approvedDefinition, validator);
        return response(versionRepository.saveAndFlush(version));
    }

    @Transactional
    public PolicyResponse supersede(UUID policyId, SupersedePolicyRequest request, UUID actorId,
                                    String idempotencyKey, String correlationId) {
        String requestHash = eventWriter.digest(request);
        UUID eventId = eventWriter.eventId(actorId, "POLICY_SUPERSEDED", idempotencyKey, policyId);
        if (eventWriter.exists(eventId, requestHash, policyId)) {
            return response(requirePolicy(policyId));
        }
        lockActivationSet();
        LifecyclePolicyVersion version = lockPolicy(policyId);
        if (eventWriter.exists(eventId, requestHash, policyId)) {
            return response(version);
        }
        if (!"APPROVED".equals(version.getStatus())) {
            throw conflict("POLICY_NOT_APPROVED", "Only an approved policy can be superseded");
        }
        if (request.effectiveTo().isBefore(clock.instant())) {
            throw conflict("RETROACTIVE_SUPERSESSION_UNSUPPORTED",
                    "Retroactive supersession requires an approved recalculation workflow");
        }
        if (version.getEffectiveTo() != null && !version.getEffectiveTo().equals(request.effectiveTo())) {
            throw conflict("POLICY_ALREADY_BOUNDED", "Policy effective end is already established");
        }
        String beforeDigest = policyDigest(version,
                ruleRepository.findByPolicyVersion_PolicyVersionIdOrderByRuleCode(policyId));
        String afterDigest = eventWriter.digest(Map.of("before", beforeDigest,
                "status", "SUPERSEDED", "effectiveTo", request.effectiveTo()));
        eventWriter.append(actorId, "POLICY_SUPERSEDED", idempotencyKey, requestHash, policyId, policyId,
                "POLICY_VERSION", "SUCCEEDED", beforeDigest, afterDigest, correlationId,
                Map.of("effectiveTo", request.effectiveTo().toString()));
        version.supersede(request.effectiveTo());
        return response(versionRepository.saveAndFlush(version));
    }

    @Transactional
    public PolicyResponse withdraw(UUID policyId, UUID actorId, String idempotencyKey, String correlationId) {
        String requestHash = eventWriter.digest(Map.of("policyVersionId", policyId, "operation", "WITHDRAW"));
        UUID eventId = eventWriter.eventId(actorId, "POLICY_WITHDRAWN", idempotencyKey, policyId);
        if (eventWriter.exists(eventId, requestHash, policyId)) {
            return response(requirePolicy(policyId));
        }
        LifecyclePolicyVersion version = lockPolicy(policyId);
        if (eventWriter.exists(eventId, requestHash, policyId)) {
            return response(version);
        }
        if (!"APPROVED".equals(version.getStatus())) {
            throw conflict("POLICY_NOT_APPROVED", "Only an approved policy can be withdrawn");
        }
        String beforeDigest = policyDigest(version,
                ruleRepository.findByPolicyVersion_PolicyVersionIdOrderByRuleCode(policyId));
        String afterDigest = eventWriter.digest(Map.of("before", beforeDigest, "status", "WITHDRAWN"));
        eventWriter.append(actorId, "POLICY_WITHDRAWN", idempotencyKey, requestHash, policyId, policyId,
                "POLICY_VERSION", "SUCCEEDED", beforeDigest, afterDigest, correlationId, Map.of());
        version.withdraw();
        return response(versionRepository.saveAndFlush(version));
    }

    private List<String> validationErrors(LifecyclePolicyVersion version) {
        List<String> errors = new ArrayList<>();
        if (version.getStatus() != null && !"DRAFT".equals(version.getStatus())) {
            errors.add("POLICY_NOT_DRAFT");
        }
        List<LifecyclePolicyRule> rules =
                ruleRepository.findByPolicyVersion_PolicyVersionIdOrderByRuleCode(version.getPolicyVersionId());
        if (rules.isEmpty()) {
            errors.add("POLICY_RULES_REQUIRED");
        }
        for (LifecyclePolicyRule rule : rules) {
            try {
                validator.validateRule(ruleDefinition(rule));
            } catch (IllegalArgumentException exception) {
                errors.add("POLICY_RULE_INVALID:" + rule.getRuleCode());
            }
        }
        try {
            validator.validateVersion(draftDefinition(version));
        } catch (IllegalArgumentException exception) {
            errors.add("POLICY_VERSION_INVALID");
        }
        if (errors.size() > 50) {
            return errors.subList(0, 50);
        }
        return List.copyOf(errors);
    }

    private JsonEventMetadata eventMetadata(UUID eventId) {
        String json = jdbcTemplate.query("SELECT metadata::text FROM lifecycle_control_event WHERE event_id = ?",
                resultSet -> resultSet.next() ? resultSet.getString(1) : null, eventId);
        if (json == null) {
            throw new IllegalStateException("Lifecycle event disappeared after idempotency lookup");
        }
        try {
            tools.jackson.databind.JsonNode node =
                    new tools.jackson.databind.ObjectMapper().readTree(json);
            List<String> codes = new ArrayList<>();
            var value = node.get("validationCodes");
            if (value != null && value.isArray()) {
                value.forEach(item -> codes.add(item.asString()));
            }
            return new JsonEventMetadata("SUCCEEDED".equals(node.path("outcome").asString()), codes);
        } catch (RuntimeException exception) {
            throw new IllegalStateException("Stored lifecycle validation evidence is invalid", exception);
        }
    }

    private static PolicyValidationResponse validationResponse(UUID policyId, JsonEventMetadata metadata) {
        return new PolicyValidationResponse(policyId, metadata.valid(), metadata.codes());
    }

    private LifecyclePolicyVersion requirePolicy(UUID id) {
        return versionRepository.findById(id)
                .orElseThrow(() -> notFound("POLICY_VERSION_NOT_FOUND", "Policy version was not found"));
    }

    private LifecyclePolicyVersion lockPolicy(UUID id) {
        return versionRepository.findByIdForUpdate(id)
                .orElseThrow(() -> notFound("POLICY_VERSION_NOT_FOUND", "Policy version was not found"));
    }

    private LifecyclePolicyRule requireRule(UUID id) {
        return ruleRepository.findById(id)
                .orElseThrow(() -> notFound("POLICY_RULE_NOT_FOUND", "Policy rule was not found"));
    }

    private static void requireDraft(LifecyclePolicyVersion version) {
        if (!"DRAFT".equals(version.getStatus())) {
            throw conflict("POLICY_CONTENT_IMMUTABLE", "Only draft policy content can be changed");
        }
    }

    private void lockActivationSet() {
        jdbcTemplate.execute((org.springframework.jdbc.core.ConnectionCallback<Void>) connection -> {
            try (var statement = connection.prepareStatement("SELECT pg_advisory_xact_lock(718239)")) {
                statement.execute();
            }
            return null;
        });
    }

    private void lockNewAggregate(UUID policyId) {
        eventWriter.lockNewAggregate(policyId);
    }

    private PolicyVersionDefinition draftDefinition(LifecyclePolicyVersion version) {
        return new PolicyVersionDefinition(version.getPolicyVersionId(), version.getVersionLabel(),
                version.getDocumentSha256(), null, null, null, null, null, null, null, null, null,
                PolicyStatus.DRAFT, version.getCreatedAt());
    }

    private static PolicyRuleDefinition ruleDefinition(UUID policyId, PolicyRuleRequest request, Instant createdAt) {
        return new PolicyRuleDefinition(request.ruleId(), policyId, request.ruleCode(), request.categoryCode(),
                request.ruleSource(), request.durationValue(), request.durationUnit(), request.startBasisCode(),
                request.scheduleReference(), createdAt);
    }

    private static PolicyRuleDefinition ruleDefinition(LifecyclePolicyRule rule) {
        return new PolicyRuleDefinition(rule.getRuleId(), rule.getPolicyVersionId(), rule.getRuleCode(),
                rule.getCategoryCode(), PolicyRuleSource.valueOf(rule.getRuleSource()), rule.getDurationValue(),
                rule.getDurationUnit() == null ? null : RetentionDurationUnit.valueOf(rule.getDurationUnit()),
                rule.getStartBasisCode(), rule.getScheduleReference(), rule.getCreatedAt());
    }

    private String policyDigest(LifecyclePolicyVersion version, List<?> rules) {
        return eventWriter.digest(Map.ofEntries(
                Map.entry("policyVersionId", version.getPolicyVersionId()),
                Map.entry("versionLabel", version.getVersionLabel()),
                Map.entry("documentSha256", version.getDocumentSha256()),
                Map.entry("approvalReference", nullable(version.getApprovalReference())),
                Map.entry("policyVersion", nullable(version.getPolicyVersion())),
                Map.entry("effectiveFrom", nullable(version.getEffectiveFrom())),
                Map.entry("effectiveTo", nullable(version.getEffectiveTo())),
                Map.entry("approvingAuthorityRef", nullable(version.getApprovingAuthorityRef())),
                Map.entry("approvedBySubject", nullable(version.getApprovedBySubject())),
                Map.entry("approvedAt", nullable(version.getApprovedAt())),
                Map.entry("legalValidationRef", nullable(version.getLegalValidationRef())),
                Map.entry("recordsValidationRef", nullable(version.getRecordsValidationRef())),
                Map.entry("status", version.getStatus()),
                Map.entry("rules", rules.stream().map(rule -> rule instanceof LifecyclePolicyRule entity
                        ? ruleState(entity) : rule instanceof PolicyRuleRequest request
                        ? ruleState(ruleDefinition(version.getPolicyVersionId(), request, version.getCreatedAt()))
                        : rule instanceof PolicyRuleDefinition definition ? ruleState(definition) : rule).toList())));
    }

    private String activatedPolicyDigest(LifecyclePolicyVersion version, PolicyVersionDefinition approved) {
        return eventWriter.digest(Map.ofEntries(
                Map.entry("policyVersionId", approved.policyVersionId()),
                Map.entry("versionLabel", approved.versionLabel()),
                Map.entry("documentSha256", approved.documentSha256()),
                Map.entry("approvalReference", approved.approvalReference()),
                Map.entry("policyVersion", approved.policyVersion()),
                Map.entry("effectiveFrom", approved.effectiveFrom()),
                Map.entry("effectiveTo", nullable(approved.effectiveTo())),
                Map.entry("approvingAuthorityRef", approved.approvingAuthorityRef()),
                Map.entry("approvedBySubject", approved.approvedBySubject()),
                Map.entry("approvedAt", approved.approvedAt()),
                Map.entry("legalValidationRef", approved.legalValidationRef()),
                Map.entry("recordsValidationRef", approved.recordsValidationRef()),
                Map.entry("status", "APPROVED"),
                Map.entry("rules", ruleRepository.findByPolicyVersion_PolicyVersionIdOrderByRuleCode(
                        version.getPolicyVersionId()).stream()
                        .map((LifecyclePolicyRule rule) -> ruleState(rule)).toList())));
    }

    private static Map<String, Object> ruleState(LifecyclePolicyRule rule) {
        return Map.of("ruleId", rule.getRuleId(), "policyVersionId", rule.getPolicyVersionId(),
                "ruleCode", rule.getRuleCode(), "categoryCode", rule.getCategoryCode(),
                "ruleSource", rule.getRuleSource(), "durationValue", nullable(rule.getDurationValue()),
                "durationUnit", nullable(rule.getDurationUnit()), "startBasisCode", rule.getStartBasisCode(),
                "scheduleReference", nullable(rule.getScheduleReference()));
    }

    private static Map<String, Object> ruleState(PolicyRuleDefinition rule) {
        return Map.of("ruleId", rule.ruleId(), "policyVersionId", rule.policyVersionId(),
                "ruleCode", rule.ruleCode(), "categoryCode", rule.categoryCode(),
                "ruleSource", rule.ruleSource().name(), "durationValue", nullable(rule.durationValue()),
                "durationUnit", nullable(rule.durationUnit() == null ? null : rule.durationUnit().name()),
                "startBasisCode", rule.startBasisCode(), "scheduleReference", nullable(rule.scheduleReference()));
    }

    private static Object nullable(Object value) {
        return value == null ? "" : value;
    }

    private PolicyResponse response(LifecyclePolicyVersion version) {
        List<PolicyRuleResponse> rules =
                ruleRepository.findByPolicyVersion_PolicyVersionIdOrderByRuleCode(version.getPolicyVersionId())
                        .stream().map(PolicyLifecycleService::ruleResponse).toList();
        return new PolicyResponse(version.getPolicyVersionId(), version.getVersionLabel(),
                version.getDocumentSha256(), version.getApprovalReference(), version.getPolicyVersion(),
                version.getEffectiveFrom(), version.getEffectiveTo(), version.getApprovingAuthorityRef(),
                version.getApprovedBySubject(), version.getApprovedAt(), version.getLegalValidationRef(),
                version.getRecordsValidationRef(), version.getStatus(), version.getCreatedAt(), rules);
    }

    private static PolicyRuleResponse ruleResponse(LifecyclePolicyRule rule) {
        return new PolicyRuleResponse(rule.getRuleId(), rule.getRuleCode(), rule.getCategoryCode(),
                rule.getRuleSource(), rule.getDurationValue(), rule.getDurationUnit(),
                rule.getStartBasisCode(), rule.getScheduleReference());
    }

    private static DomainException notFound(String code, String message) {
        return new DomainException(HttpStatus.NOT_FOUND, code, message);
    }

    private static DomainException conflict(String code, String message) {
        return new DomainException(HttpStatus.CONFLICT, code, message);
    }

    private static DomainException badRequest(String code, String message) {
        return new DomainException(HttpStatus.BAD_REQUEST, code, message);
    }

    private record JsonEventMetadata(boolean valid, List<String> codes) {
    }
}
