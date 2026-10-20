-- Claims include paused channels, quiet hours and other deferrals. Only retryable
-- provider failures consume the delivery retry budget or increase its backoff.
ALTER TABLE delivery_jobs ADD COLUMN provider_failure_count integer NOT NULL DEFAULT 0
    CHECK (provider_failure_count >= 0);

-- Recover known failures for existing jobs from the durable attempt outcomes.
-- Never derive this value from attempt_count or reopen a terminal SMTP outcome.
UPDATE delivery_jobs j SET provider_failure_count = failures.total
FROM (
    SELECT delivery_id, count(*)::integer AS total
    FROM delivery_attempts
    WHERE safe_code IN (
        'APNS_RATE_LIMIT', 'APNS_UNAVAILABLE', 'APNS_NETWORK',
        'FCM_RATE_LIMIT', 'FCM_UNAVAILABLE', 'FCM_NETWORK',
        'SMTP_CONNECT', 'SMTP_REJECTED_TEMPORARY',
        'RETRIES_EXHAUSTED', 'RETRY_AFTER_EXPIRY'
    ) AND outcome IN ('RETRY', 'FAILED', 'EXPIRED')
    GROUP BY delivery_id
) failures
WHERE j.id = failures.delivery_id;
