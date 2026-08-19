CREATE TABLE agent (
    id                uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    full_name         text NOT NULL,
    email             citext NOT NULL UNIQUE,
    password_hash     text NOT NULL,
    israeli_id        text NOT NULL,
    agency_name       text NOT NULL,
    mobile            text NOT NULL,
    agent_number      text NOT NULL UNIQUE,
    logo_id           uuid NULL,
    is_active         boolean NOT NULL DEFAULT true,
    trial_started_at  timestamptz NOT NULL DEFAULT now(),
    created_at        timestamptz NOT NULL DEFAULT now(),
    updated_at        timestamptz NOT NULL DEFAULT now()
);
