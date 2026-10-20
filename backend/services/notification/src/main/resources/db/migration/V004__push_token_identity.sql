-- A 4096-character opaque token can exceed a PostgreSQL B-tree tuple limit.
-- Keep the original token for delivery and compare it alongside its fixed-size
-- digest when moving ownership. A hash collision fails closed at uniqueness.
DROP INDEX push_devices_active_token;

-- Existing APNs case aliases identify the same hexadecimal bytes. Preserve the
-- most recently registered active owner, using id as a deterministic tie-breaker.
-- Disabled history must never displace an active registration.
WITH ranked AS (
    SELECT id, row_number() OVER (
        PARTITION BY platform, CASE
            WHEN platform='IOS' AND length(push_token) BETWEEN 32 AND 512
                AND push_token ~ '^[0-9a-fA-F]+$' THEN lower(push_token)
            ELSE push_token END
        ORDER BY updated_at DESC,id DESC
    ) AS position
    FROM push_devices WHERE enabled
)
UPDATE push_devices d SET enabled=false,updated_at=clock_timestamp()
FROM ranked r WHERE d.id=r.id AND r.position>1;

ALTER TABLE push_devices ADD COLUMN push_token_hash bytea;
UPDATE push_devices SET
    updated_at=CASE WHEN platform='IOS' AND length(push_token) BETWEEN 32 AND 512 AND push_token ~ '^[0-9a-fA-F]+$'
        AND push_token<>lower(push_token) THEN clock_timestamp() ELSE updated_at END,
    push_token=CASE WHEN platform='IOS' AND length(push_token) BETWEEN 32 AND 512 AND push_token ~ '^[0-9a-fA-F]+$'
        THEN lower(push_token) ELSE push_token END;
UPDATE push_devices SET push_token_hash=sha256(convert_to(push_token,'UTF8'));
ALTER TABLE push_devices ALTER COLUMN push_token_hash SET NOT NULL;
ALTER TABLE push_devices ADD CONSTRAINT push_devices_token_hash_size CHECK (octet_length(push_token_hash)=32);

-- Use a trigger instead of falsely declaring convert_to immutable. This also
-- protects direct SQL inserts and platform changes from stale token identity.
CREATE FUNCTION notification_set_push_token_identity() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.platform='IOS' AND length(NEW.push_token) BETWEEN 32 AND 512 AND NEW.push_token ~ '^[0-9a-fA-F]+$' THEN
        NEW.push_token := lower(NEW.push_token);
    END IF;
    NEW.push_token_hash := sha256(convert_to(NEW.push_token,'UTF8'));
    RETURN NEW;
END;
$$;
CREATE TRIGGER push_devices_token_identity
    BEFORE INSERT OR UPDATE OF platform,push_token,push_token_hash ON push_devices
    FOR EACH ROW EXECUTE FUNCTION notification_set_push_token_identity();

CREATE UNIQUE INDEX push_devices_active_token ON push_devices(platform,push_token_hash) WHERE enabled;
