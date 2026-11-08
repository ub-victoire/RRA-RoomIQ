package rw.rra.roomiq.lifecycle.retention;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import rw.rra.roomiq.lifecycle.policy.LifecyclePolicyRule;
import rw.rra.roomiq.lifecycle.policy.LifecyclePolicyRuleRepository;
import rw.rra.roomiq.lifecycle.policy.LifecyclePolicyVersion;
import rw.rra.roomiq.lifecycle.policy.LifecyclePolicyVersionRepository;
import rw.rra.roomiq.lifecycle.policy.PolicyRuleSource;
import rw.rra.roomiq.lifecycle.policy.PolicyStatus;
import rw.rra.roomiq.lifecycle.policy.RetentionDurationUnit;

import java.util.List;
import java.util.UUID;

@Service
public class RetentionPolicyEvaluationService {
    private final LifecyclePolicyVersionRepository versionRepository;
    private final LifecyclePolicyRuleRepository ruleRepository;
    private final RetentionEligibilityCalculator calculator;

    public RetentionPolicyEvaluationService(LifecyclePolicyVersionRepository versionRepository,
                                            LifecyclePolicyRuleRepository ruleRepository,
                                            RetentionEligibilityCalculator calculator) {
        this.versionRepository = versionRepository;
        this.ruleRepository = ruleRepository;
        this.calculator = calculator;
    }

    @Transactional(readOnly = true)
    public RetentionEvaluationResult evaluate(RetentionEvaluationEvidence evidence) {
        if (evidence == null) {
            return calculator.evaluate(null);
        }

        List<PolicyVersionSnapshot> versions = evidence.authoritativeStartAt() == null ? List.of()
                : versionRepository.findEffectiveAt(evidence.authoritativeStartAt()).stream()
                .map(RetentionPolicyEvaluationService::toSnapshot)
                .toList();
        List<UUID> versionIds = versions.stream().map(PolicyVersionSnapshot::id).toList();
        List<RetentionRuleSnapshot> rules = versionIds.isEmpty() || evidence.categoryCode() == null
                ? List.of()
                : ruleRepository.findByPolicyVersion_PolicyVersionIdInAndCategoryCode(
                        versionIds, evidence.categoryCode()).stream()
                .map(RetentionPolicyEvaluationService::toSnapshot)
                .toList();

        return calculator.evaluate(new RetentionEvaluationInput(
                evidence.ownerKnown(),
                evidence.ownerAcknowledged(),
                evidence.categoryCode(),
                evidence.authoritativeStartAt(),
                evidence.evaluationAt(),
                versions,
                rules,
                evidence.startTimestampsByBasis(),
                evidence.externalScheduleResultsByRuleCode(),
                evidence.holdStateAvailable(),
                evidence.holdStatuses()));
    }

    private static PolicyVersionSnapshot toSnapshot(LifecyclePolicyVersion version) {
        return new PolicyVersionSnapshot(version.getPolicyVersionId(), version.getPolicyVersion(),
                PolicyStatus.valueOf(version.getStatus()), version.getEffectiveFrom(), version.getEffectiveTo());
    }

    private static RetentionRuleSnapshot toSnapshot(LifecyclePolicyRule rule) {
        return new RetentionRuleSnapshot(rule.getPolicyVersionId(), rule.getRuleCode(), rule.getCategoryCode(),
                PolicyRuleSource.valueOf(rule.getRuleSource()),
                rule.getDurationValue(),
                rule.getDurationUnit() == null ? null : RetentionDurationUnit.valueOf(rule.getDurationUnit()),
                rule.getStartBasisCode(), rule.getScheduleReference());
    }
}
