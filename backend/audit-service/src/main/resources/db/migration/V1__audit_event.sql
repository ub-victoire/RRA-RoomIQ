CREATE TABLE audit_event (
    id UUID PRIMARY KEY,
    actor_user_id UUID,
    action VARCHAR(128) NOT NULL,
    resource_type VARCHAR(64) NOT NULL,
    resource_id UUID,
    scope_office_building_id UUID,
    outcome VARCHAR(32) NOT NULL,
    correlation_id VARCHAR(128),
    metadata JSONB,
    occurred_at TIMESTAMP WITH TIME ZONE NOT NULL
);
