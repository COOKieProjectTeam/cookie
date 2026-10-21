-- A service instance uses one Firebase project for both platforms. A platform
-- label must not create a second ownership namespace for the same FCM token.
LOCK TABLE push_devices IN SHARE ROW EXCLUSIVE MODE;
WITH ranked AS (
    SELECT id, row_number() OVER (
        PARTITION BY push_token_hash ORDER BY updated_at DESC,id DESC
    ) AS position
    FROM push_devices WHERE enabled
)
UPDATE push_devices d SET enabled=false,updated_at=clock_timestamp()
FROM ranked r WHERE d.id=r.id AND r.position>1;

DROP INDEX push_devices_active_token;
CREATE UNIQUE INDEX push_devices_active_token ON push_devices(push_token_hash) WHERE enabled;
