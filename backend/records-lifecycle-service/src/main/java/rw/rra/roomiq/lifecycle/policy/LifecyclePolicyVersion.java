package rw.rra.roomiq.lifecycle.policy;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "lifecycle_policy_version")
public class LifecyclePolicyVersion {
    @Id
    @Column(name = "policy_version_id", nullable = false)
    private UUID policyVersionId;

    @Column(name = "version_label", nullable = false, length = 64, unique = true)
    private String versionLabel;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "document_sha256", nullable = false, columnDefinition = "char(64)", length = 64)
    private String documentSha256;

    @Column(name = "approval_reference", length = 128)
    private String approvalReference;

    @Column(name = "policy_version", length = 64)
    private String policyVersion;

    @Column(name = "effective_from")
    private Instant effectiveFrom;

    @Column(name = "effective_to")
    private Instant effectiveTo;

    @Column(name = "approving_authority_ref", length = 128)
    private String approvingAuthorityRef;

    @Column(name = "approved_by_subject", length = 128)
    private String approvedBySubject;

    @Column(name = "approved_at")
    private Instant approvedAt;

    @Column(name = "legal_validation_ref", length = 128)
    private String legalValidationRef;

    @Column(name = "records_validation_ref", length = 128)
    private String recordsValidationRef;

    @Column(nullable = false, length = 16)
    private String status;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected LifecyclePolicyVersion() {
    }

    public LifecyclePolicyVersion(PolicyVersionDefinition definition, PolicyDefinitionValidator validator) {
        validator.validateVersion(definition);
        this.policyVersionId = definition.policyVersionId();
        this.versionLabel = definition.versionLabel();
        this.documentSha256 = definition.documentSha256();
        this.approvalReference = definition.approvalReference();
        this.policyVersion = definition.policyVersion();
        this.effectiveFrom = definition.effectiveFrom();
        this.effectiveTo = definition.effectiveTo();
        this.approvingAuthorityRef = definition.approvingAuthorityRef();
        this.approvedBySubject = definition.approvedBySubject();
        this.approvedAt = definition.approvedAt();
        this.legalValidationRef = definition.legalValidationRef();
        this.recordsValidationRef = definition.recordsValidationRef();
        this.status = definition.status().name();
        this.createdAt = definition.createdAt();
    }

    public void activate(PolicyVersionDefinition definition, PolicyDefinitionValidator validator) {
        if (!"DRAFT".equals(status)) {
            throw new IllegalStateException("Only a draft policy can be activated");
        }
        validator.validateVersion(definition);
        if (!policyVersionId.equals(definition.policyVersionId())
                || !versionLabel.equals(definition.versionLabel())
                || !documentSha256.equalsIgnoreCase(definition.documentSha256())) {
            throw new IllegalArgumentException("Activation cannot change the policy identity or document digest");
        }
        approvalReference = definition.approvalReference();
        policyVersion = definition.policyVersion();
        effectiveFrom = definition.effectiveFrom();
        effectiveTo = definition.effectiveTo();
        approvingAuthorityRef = definition.approvingAuthorityRef();
        approvedBySubject = definition.approvedBySubject();
        approvedAt = definition.approvedAt();
        legalValidationRef = definition.legalValidationRef();
        recordsValidationRef = definition.recordsValidationRef();
        status = "APPROVED";
    }

    public void supersede(Instant effectiveTo) {
        if (!"APPROVED".equals(status) || effectiveFrom == null || effectiveTo == null
                || !effectiveTo.isAfter(effectiveFrom)) {
            throw new IllegalStateException("Only an approved policy can be superseded at a valid boundary");
        }
        this.effectiveTo = effectiveTo;
        this.status = "SUPERSEDED";
    }

    public void withdraw() {
        if (!"APPROVED".equals(status)) {
            throw new IllegalStateException("Only an approved policy can be withdrawn");
        }
        status = "WITHDRAWN";
    }

    public UUID getPolicyVersionId() { return policyVersionId; }
    public String getVersionLabel() { return versionLabel; }
    public String getDocumentSha256() { return documentSha256; }
    public String getApprovalReference() { return approvalReference; }
    public String getPolicyVersion() { return policyVersion; }
    public Instant getEffectiveFrom() { return effectiveFrom; }
    public Instant getEffectiveTo() { return effectiveTo; }
    public String getApprovingAuthorityRef() { return approvingAuthorityRef; }
    public String getApprovedBySubject() { return approvedBySubject; }
    public Instant getApprovedAt() { return approvedAt; }
    public String getLegalValidationRef() { return legalValidationRef; }
    public String getRecordsValidationRef() { return recordsValidationRef; }
    public String getStatus() { return status; }
    public Instant getCreatedAt() { return createdAt; }
}
