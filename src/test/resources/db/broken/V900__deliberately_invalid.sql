-- Issue #16 negative control. This file is never on the application's Flyway
-- path; a test adds this location on purpose to prove that a bad migration
-- stops the Spring context from starting instead of being silently skipped.
CREATE TABLE this_statement_is_not_valid_sql (;
