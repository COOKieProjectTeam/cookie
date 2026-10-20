-- Both IOS and ANDROID now register opaque, case-sensitive FCM tokens.
-- Keep V004's bounded digest index, but never normalize incoming token bytes.
CREATE OR REPLACE FUNCTION notification_set_push_token_identity() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    NEW.push_token_hash := sha256(convert_to(NEW.push_token,'UTF8'));
    RETURN NEW;
END;
$$;

-- APNs device tokens cannot be converted into FCM registration tokens. Every
-- existing iOS installation must register its FCM token after this migration.
-- Updating the revision also fences late invalid-token responses.
UPDATE push_devices SET enabled=false,updated_at=clock_timestamp()
WHERE platform='IOS';

-- Re-registration can re-arm eligible unsent reminders through the normal
-- scheduler. Preserve submission evidence, terminal outcomes and all email jobs.
WITH legacy_ios_jobs AS MATERIALIZED (
    SELECT j.id,j.lease_token
    FROM delivery_jobs j JOIN push_devices d ON d.id=j.device_id
    WHERE j.channel='PUSH' AND d.platform='IOS' AND j.status IN ('PENDING','CLAIMED')
    FOR UPDATE OF j
), cancelled AS (
    UPDATE delivery_jobs j SET status='CANCELLED',finished_at=clock_timestamp(),
        lease_token=NULL,lease_until=NULL,last_code='IOS_FCM_REREGISTRATION_REQUIRED'
    FROM legacy_ios_jobs old WHERE j.id=old.id
    RETURNING old.lease_token
)
UPDATE delivery_attempts a SET outcome='CANCELLED',
    safe_code='IOS_FCM_REREGISTRATION_REQUIRED',finished_at=clock_timestamp()
FROM cancelled c WHERE a.id=c.lease_token AND a.finished_at IS NULL;
