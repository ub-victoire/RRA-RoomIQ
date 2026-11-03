CREATE ROLE roomiq_audit_migrator LOGIN PASSWORD 'audit-migrator-test-password';
CREATE ROLE roomiq_audit_app LOGIN PASSWORD 'audit-app-test-password';

GRANT USAGE, CREATE ON SCHEMA public TO roomiq_audit_migrator;
GRANT USAGE ON SCHEMA public TO roomiq_audit_app;
