CREATE TABLE player_profiles (
    player_uuid UUID PRIMARY KEY,
    current_name VARCHAR(16) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    last_seen_at TIMESTAMPTZ NOT NULL,
    profile_version INTEGER NOT NULL DEFAULT 1,
    CONSTRAINT player_profiles_profile_version_positive CHECK (profile_version > 0)
);
