CREATE INDEX idx_outbox_events_published_cleanup
    ON outbox_events(published_at, event_id)
    WHERE published_at IS NOT NULL;
