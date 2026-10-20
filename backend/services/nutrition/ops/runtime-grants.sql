-- Apply as schema owner after migrations; provision nutrition_runtime LOGIN separately.
REVOKE ALL ON SCHEMA public FROM PUBLIC;
GRANT USAGE ON SCHEMA public TO nutrition_runtime;
REVOKE ALL ON ALL TABLES IN SCHEMA public FROM nutrition_runtime;
GRANT SELECT, INSERT, UPDATE, DELETE ON meals, saved_dishes, water_entries, day_versions, outbox_events TO nutrition_runtime;
GRANT SELECT, INSERT ON account_tombstones, inbox_events, rejected_events TO nutrition_runtime;
