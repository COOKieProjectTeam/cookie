CREATE TABLE account_tombstones (
    user_id uuid PRIMARY KEY,
    deletion_request_id uuid NOT NULL,
    deleted_at timestamptz NOT NULL
);
CREATE TABLE settings (
    user_id uuid PRIMARY KEY,
    water_enabled boolean NOT NULL DEFAULT true,
    food_enabled boolean NOT NULL DEFAULT true,
    food_inactive_minutes integer NOT NULL DEFAULT 240 CHECK (food_inactive_minutes BETWEEN 1 AND 10080),
    weigh_enabled boolean NOT NULL DEFAULT true,
    weigh_time time NOT NULL DEFAULT '08:00',
    goal_warning_enabled boolean NOT NULL DEFAULT true,
    quiet_start time NOT NULL DEFAULT '23:00',
    quiet_end time NOT NULL DEFAULT '08:00',
    timezone varchar(255),
    locale varchar(255) NOT NULL DEFAULT 'ru',
    locale_overridden boolean NOT NULL DEFAULT false,
    scheduling_started_at timestamptz,
    food_started_at timestamptz,
    water_started_at timestamptz,
    last_water_reminder_at timestamptz,
    updated_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
CREATE TABLE push_devices (
    id uuid PRIMARY KEY,
    user_id uuid NOT NULL,
    platform varchar(10) NOT NULL CHECK (platform IN ('IOS','ANDROID')),
    device_id varchar(255) NOT NULL,
    push_token varchar(4096) NOT NULL,
    enabled boolean NOT NULL DEFAULT true,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    UNIQUE (user_id,device_id)
);
CREATE UNIQUE INDEX push_devices_active_token ON push_devices(platform,push_token) WHERE enabled;
CREATE INDEX push_devices_user ON push_devices(user_id);
CREATE TABLE inbox_events (
    consumer_name varchar(100) NOT NULL,
    event_id uuid NOT NULL,
    event_type varchar(100) NOT NULL,
    processed_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY(consumer_name,event_id)
);
CREATE INDEX inbox_events_retention ON inbox_events(processed_at);
CREATE TABLE event_projections (
    kind varchar(30) NOT NULL,
    user_id uuid NOT NULL,
    entity_id varchar(255) NOT NULL,
    version bigint NOT NULL CHECK(version > 0),
    payload jsonb NOT NULL,
    deleted boolean NOT NULL DEFAULT false,
    updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY(kind,user_id,entity_id)
);
CREATE INDEX event_projections_user ON event_projections(user_id,kind);
CREATE TABLE reminder_deadlines (
    user_id uuid PRIMARY KEY,
    due_at timestamptz NOT NULL,
    generation bigint NOT NULL DEFAULT 1
);
CREATE INDEX reminder_deadlines_due ON reminder_deadlines(due_at,user_id);
CREATE TABLE reminder_occurrences (
    id uuid PRIMARY KEY,
    user_id uuid NOT NULL,
    kind varchar(40) NOT NULL,
    cycle_key varchar(255) NOT NULL,
    scheduled_at timestamptz NOT NULL,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    UNIQUE(user_id,kind,cycle_key)
);
CREATE INDEX reminder_occurrences_user ON reminder_occurrences(user_id);
CREATE TABLE message_quarantine (
    id uuid PRIMARY KEY,
    message_hash char(64) NOT NULL UNIQUE,
    event_id uuid,
    subject varchar(255) NOT NULL,
    reason varchar(100) NOT NULL,
    stream_sequence bigint NOT NULL,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    replay_requested_at timestamptz,
    replayed_at timestamptz,
    replay_attempts integer NOT NULL DEFAULT 0
);
CREATE INDEX quarantine_replay ON message_quarantine(replay_requested_at) WHERE replayed_at IS NULL;

CREATE INDEX quarantine_retention ON message_quarantine(created_at);
