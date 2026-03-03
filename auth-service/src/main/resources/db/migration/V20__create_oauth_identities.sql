-- OAuth identity management tables
CREATE TABLE oauth_identities (
    id              UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    provider        VARCHAR(50) NOT NULL DEFAULT 'yandex',
    provider_sub    VARCHAR(255) NOT NULL,
    email           VARCHAR(255) NOT NULL,
    name            VARCHAR(255),
    avatar_url      VARCHAR(1024),
    raw_attributes  JSONB       NOT NULL DEFAULT '{}',
    created_ts      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_ts      TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_oauth_provider_sub UNIQUE (provider, provider_sub)
);
CREATE INDEX idx_oauth_identities_email ON oauth_identities (email);

CREATE TABLE user_oauth_links (
    id              UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id         UUID        NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    oauth_id        UUID        NOT NULL REFERENCES oauth_identities(id) ON DELETE CASCADE,
    linked_ts       TIMESTAMPTZ NOT NULL DEFAULT now(),
    linked_by       UUID        REFERENCES users(id),
    CONSTRAINT uq_user_oauth UNIQUE (user_id, oauth_id)
);
CREATE INDEX idx_user_oauth_links_oauth_id ON user_oauth_links (oauth_id);
CREATE INDEX idx_user_oauth_links_user_id ON user_oauth_links (user_id);
