CREATE OR REPLACE FUNCTION lifecycle_policy_reject_untracked_mutation()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
    configured_event_id TEXT;
    event_row lifecycle_control_event%ROWTYPE;
    expected_event_type VARCHAR(48);
    policy_id UUID;
    resource_id UUID;
    resource_type TEXT;
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'policy history cannot be deleted'
            USING ERRCODE = '55000';
    END IF;

    configured_event_id := NULLIF(current_setting('roomiq.lifecycle.event_id', TRUE), '');
    IF configured_event_id IS NULL THEN
        RAISE EXCEPTION 'policy mutation requires an audited lifecycle event in the same transaction'
            USING ERRCODE = '55000';
    END IF;

    SELECT * INTO event_row
    FROM lifecycle_control_event
    WHERE event_id = configured_event_id::UUID;
    IF NOT FOUND OR event_row.aggregate_type <> 'POLICY' THEN
        RAISE EXCEPTION 'policy mutation lifecycle event is missing or invalid'
            USING ERRCODE = '55000';
    END IF;

    IF TG_TABLE_NAME = 'lifecycle_policy_version' THEN
        policy_id := NEW.policy_version_id;
        resource_type := 'POLICY_VERSION';
        resource_id := NEW.policy_version_id;
        IF TG_OP = 'INSERT' THEN
            IF NEW.status <> 'DRAFT' OR NEW.approval_reference IS NOT NULL
                    OR NEW.policy_version IS NOT NULL OR NEW.effective_from IS NOT NULL
                    OR NEW.effective_to IS NOT NULL OR NEW.approved_by_subject IS NOT NULL
                    OR NEW.approved_at IS NOT NULL THEN
                RAISE EXCEPTION 'policy versions must be created as unapproved drafts'
                    USING ERRCODE = '55000';
            END IF;
            expected_event_type := 'POLICY_CREATED';
        ELSIF OLD.status = 'DRAFT' AND NEW.status = 'APPROVED' THEN
            IF NEW.version_label <> OLD.version_label
                    OR NEW.document_sha256 <> OLD.document_sha256
                    OR NEW.created_at <> OLD.created_at THEN
                RAISE EXCEPTION 'activation cannot rewrite draft policy content'
                    USING ERRCODE = '55000';
            END IF;
            expected_event_type := 'POLICY_ACTIVATED';
        ELSIF OLD.status = 'APPROVED' AND NEW.status = 'SUPERSEDED' THEN
            IF NEW.version_label <> OLD.version_label
                    OR NEW.document_sha256 <> OLD.document_sha256
                    OR NEW.approval_reference <> OLD.approval_reference
                    OR NEW.policy_version <> OLD.policy_version
                    OR NEW.effective_from <> OLD.effective_from
                    OR NEW.approving_authority_ref <> OLD.approving_authority_ref
                    OR NEW.approved_by_subject <> OLD.approved_by_subject
                    OR NEW.approved_at <> OLD.approved_at
                    OR NEW.legal_validation_ref <> OLD.legal_validation_ref
                    OR NEW.records_validation_ref <> OLD.records_validation_ref
                    OR NEW.created_at <> OLD.created_at
                    OR NEW.effective_to IS NULL
                    OR (OLD.effective_to IS NOT NULL AND NEW.effective_to > OLD.effective_to) THEN
                RAISE EXCEPTION 'supersession may only establish an earlier effective end'
                    USING ERRCODE = '55000';
            END IF;
            expected_event_type := 'POLICY_SUPERSEDED';
        ELSIF OLD.status = 'APPROVED' AND NEW.status = 'WITHDRAWN' THEN
            IF NEW.version_label <> OLD.version_label
                    OR NEW.document_sha256 <> OLD.document_sha256
                    OR NEW.approval_reference <> OLD.approval_reference
                    OR NEW.policy_version <> OLD.policy_version
                    OR NEW.effective_from <> OLD.effective_from
                    OR NEW.effective_to IS DISTINCT FROM OLD.effective_to
                    OR NEW.approving_authority_ref <> OLD.approving_authority_ref
                    OR NEW.approved_by_subject <> OLD.approved_by_subject
                    OR NEW.approved_at <> OLD.approved_at
                    OR NEW.legal_validation_ref <> OLD.legal_validation_ref
                    OR NEW.records_validation_ref <> OLD.records_validation_ref
                    OR NEW.created_at <> OLD.created_at THEN
                RAISE EXCEPTION 'withdrawal cannot rewrite approved policy content'
                    USING ERRCODE = '55000';
            END IF;
            expected_event_type := 'POLICY_WITHDRAWN';
        ELSE
            RAISE EXCEPTION 'unsupported policy state transition'
                USING ERRCODE = '55000';
        END IF;
    ELSIF TG_TABLE_NAME = 'lifecycle_policy_rule' THEN
        policy_id := NEW.policy_version_id;
        resource_type := 'POLICY_RULE';
        resource_id := NEW.rule_id;
        IF NOT EXISTS (
            SELECT 1 FROM lifecycle_policy_version
            WHERE policy_version_id = policy_id AND status = 'DRAFT'
        ) THEN
            RAISE EXCEPTION 'only draft policy rules may be created or modified'
                USING ERRCODE = '55000';
        END IF;
        IF TG_OP = 'INSERT' THEN
            expected_event_type := 'POLICY_RULE_CREATED';
        ELSIF TG_OP = 'UPDATE' THEN
            expected_event_type := 'POLICY_RULE_MODIFIED';
        ELSE
            RAISE EXCEPTION 'unsupported policy rule mutation'
                USING ERRCODE = '55000';
        END IF;
    ELSE
        RAISE EXCEPTION 'unsupported policy table mutation'
            USING ERRCODE = '55000';
    END IF;

    IF event_row.aggregate_id IS DISTINCT FROM policy_id
            OR event_row.event_type IS DISTINCT FROM expected_event_type
            OR event_row.metadata ->> 'resourceType' IS DISTINCT FROM resource_type
            OR event_row.metadata ->> 'resourceId' IS DISTINCT FROM resource_id::TEXT
            OR event_row.metadata ->> 'outcome' IS DISTINCT FROM 'SUCCEEDED' THEN
        RAISE EXCEPTION 'policy mutation does not match its lifecycle event'
            USING ERRCODE = '55000';
    END IF;

    PERFORM set_config('roomiq.lifecycle.event_id', '', TRUE);
    RETURN NEW;
END;
$$;
