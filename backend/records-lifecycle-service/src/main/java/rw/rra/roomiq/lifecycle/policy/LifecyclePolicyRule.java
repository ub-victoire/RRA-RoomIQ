package rw.rra.roomiq.lifecycle.policy;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "lifecycle_policy_rule",
        uniqueConstraints = @UniqueConstraint(name = "uq_lifecycle_policy_rule_code",
                columnNames = {"policy_version_id", "rule_code"}))
public class LifecyclePolicyRule {
    @Id
    @Column(name = "rule_id", nullable = false)
    private UUID ruleId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "policy_version_id", nullable = false)
    private LifecyclePolicyVersion policyVersion;

    @Column(name = "rule_code", nullable = false, length = 64)
    private String ruleCode;

    @Column(name = "category_code", nullable = false, length = 64)
    private String categoryCode;

    @Column(name = "rule_source", nullable = false, length = 24)
    private String ruleSource;

    @Column(name = "duration_value")
    private Short durationValue;

    @Column(name = "duration_unit", length = 8)
    private String durationUnit;

    @Column(name = "start_basis_code", nullable = false, length = 40)
    private String startBasisCode;

    @Column(name = "schedule_reference", length = 256)
    private String scheduleReference;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected LifecyclePolicyRule() {
    }

    public LifecyclePolicyRule(PolicyRuleDefinition definition, LifecyclePolicyVersion policyVersion,
                               PolicyDefinitionValidator validator) {
        validator.validateRule(definition);
        if (!definition.policyVersionId().equals(policyVersion.getPolicyVersionId())) {
            throw new IllegalArgumentException("Rule policy version does not match its parent");
        }
        this.ruleId = definition.ruleId();
        this.policyVersion = policyVersion;
        this.ruleCode = definition.ruleCode();
        this.categoryCode = definition.categoryCode();
        this.ruleSource = definition.ruleSource().name();
        this.durationValue = definition.durationValue();
        this.durationUnit = definition.durationUnit() == null ? null : definition.durationUnit().name();
        this.startBasisCode = definition.startBasisCode();
        this.scheduleReference = definition.scheduleReference();
        this.createdAt = definition.createdAt();
    }

    public void update(PolicyRuleDefinition definition, PolicyDefinitionValidator validator) {
        validator.validateRule(definition);
        if (!ruleId.equals(definition.ruleId())
                || !policyVersion.getPolicyVersionId().equals(definition.policyVersionId())) {
            throw new IllegalArgumentException("A policy rule update cannot change its identity or parent");
        }
        ruleCode = definition.ruleCode();
        categoryCode = definition.categoryCode();
        ruleSource = definition.ruleSource().name();
        durationValue = definition.durationValue();
        durationUnit = definition.durationUnit() == null ? null : definition.durationUnit().name();
        startBasisCode = definition.startBasisCode();
        scheduleReference = definition.scheduleReference();
    }

    public UUID getRuleId() { return ruleId; }
    public UUID getPolicyVersionId() { return policyVersion.getPolicyVersionId(); }
    public String getRuleCode() { return ruleCode; }
    public String getCategoryCode() { return categoryCode; }
    public String getRuleSource() { return ruleSource; }
    public Short getDurationValue() { return durationValue; }
    public String getDurationUnit() { return durationUnit; }
    public String getStartBasisCode() { return startBasisCode; }
    public String getScheduleReference() { return scheduleReference; }
    public Instant getCreatedAt() { return createdAt; }
}
