ALTER TABLE lifecycle_control_event
    ADD CONSTRAINT ck_lifecycle_control_event_type
    CHECK (event_type IN (
        'POLICY_CREATED',
        'POLICY_RULE_CREATED',
        'POLICY_RULE_MODIFIED',
        'POLICY_VALIDATED',
        'POLICY_ACTIVATED',
        'POLICY_SUPERSEDED',
        'POLICY_WITHDRAWN'
    ));
