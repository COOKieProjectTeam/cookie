-- Reservation time and actual provider submission are separate boundaries.
-- Evidence lives with the occurrence so FOOD remains consumed after job retention.
ALTER TABLE reminder_occurrences ADD COLUMN last_submitted_at timestamptz;
ALTER TABLE settings ADD COLUMN last_water_submission_at timestamptz;
ALTER TABLE settings ADD COLUMN last_water_submission_occurrence_id uuid;

-- Preserve already accepted work when updating an existing development volume.
UPDATE reminder_occurrences o SET last_submitted_at = evidence.submitted_at
FROM (
    SELECT occurrence_id, max(finished_at) AS submitted_at
    FROM delivery_jobs WHERE channel='PUSH' AND status IN ('ACCEPTED','UNKNOWN')
    GROUP BY occurrence_id
) evidence WHERE o.id=evidence.occurrence_id;

UPDATE settings s SET last_water_submission_at=evidence.last_submitted_at,
    last_water_submission_occurrence_id=evidence.id
FROM (
    SELECT DISTINCT ON (user_id) user_id,id,last_submitted_at
    FROM reminder_occurrences WHERE kind='WATER' AND last_submitted_at IS NOT NULL
    ORDER BY user_id,last_submitted_at DESC,id
) evidence WHERE s.user_id=evidence.user_id;
