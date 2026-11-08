DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'roomiq_rlc_app') THEN
        RAISE EXCEPTION 'Required runtime role roomiq_rlc_app must be provisioned before RLC migrations';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'roomiq_rlc_migrator') THEN
        RAISE EXCEPTION 'Required migration role roomiq_rlc_migrator must be provisioned before RLC migrations';
    END IF;
    IF EXISTS (
        SELECT 1
        FROM pg_roles
        WHERE rolname = 'roomiq_rlc_app'
          AND (rolsuper OR rolcreatedb OR rolcreaterole OR rolreplication OR rolbypassrls)
    ) THEN
        RAISE EXCEPTION 'RLC runtime role must not have elevated PostgreSQL privileges';
    END IF;
    IF EXISTS (
        SELECT 1
        FROM pg_roles
        WHERE rolname = 'roomiq_rlc_migrator'
          AND (rolsuper OR rolcreatedb OR rolcreaterole OR rolreplication OR rolbypassrls)
    ) THEN
        RAISE EXCEPTION 'RLC migration role must not have superuser or cluster-administration privileges';
    END IF;
    IF pg_has_role('roomiq_rlc_app', 'roomiq_rlc_migrator', 'MEMBER') THEN
        RAISE EXCEPTION 'RLC runtime role must not inherit migration-role privileges';
    END IF;
    IF has_schema_privilege('roomiq_rlc_app', 'public', 'CREATE') THEN
        RAISE EXCEPTION 'RLC runtime role must not be able to create objects in the public schema';
    END IF;
    IF EXISTS (
        SELECT 1
        FROM pg_class c
        JOIN pg_namespace n ON n.oid = c.relnamespace
        WHERE n.nspname = 'public'
          AND c.relname IN ('lifecycle_control_event', 'lifecycle_policy_version', 'lifecycle_policy_rule')
          AND pg_has_role('roomiq_rlc_app', pg_get_userbyid(c.relowner), 'MEMBER')
    ) THEN
        RAISE EXCEPTION 'RLC runtime role must not inherit ownership of lifecycle tables';
    END IF;
END;
$$;

GRANT USAGE ON SCHEMA public TO roomiq_rlc_app;
REVOKE ALL PRIVILEGES ON TABLE
    lifecycle_control_event,
    lifecycle_policy_version,
    lifecycle_policy_rule
FROM PUBLIC, roomiq_rlc_app;

GRANT SELECT, INSERT ON TABLE lifecycle_control_event TO roomiq_rlc_app;
GRANT SELECT, INSERT, UPDATE ON TABLE lifecycle_policy_version, lifecycle_policy_rule TO roomiq_rlc_app;

REVOKE ALL PRIVILEGES ON FUNCTION
    lifecycle_control_event_reject_mutation(),
    lifecycle_policy_reject_untracked_mutation()
FROM PUBLIC, roomiq_rlc_app;
