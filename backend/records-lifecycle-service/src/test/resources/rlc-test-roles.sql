CREATE ROLE roomiq_rlc_migrator LOGIN PASSWORD 'rlc-migrator-test-password';
CREATE ROLE roomiq_rlc_app LOGIN PASSWORD 'rlc-app-test-password';

GRANT USAGE, CREATE ON SCHEMA public TO roomiq_rlc_migrator;
