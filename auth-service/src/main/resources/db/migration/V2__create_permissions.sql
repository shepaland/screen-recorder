CREATE TABLE permissions (
    id              UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    code            VARCHAR(100) NOT NULL UNIQUE,
    name            VARCHAR(255) NOT NULL,
    description     TEXT,
    resource        VARCHAR(100) NOT NULL,
    action          VARCHAR(50)  NOT NULL,
    created_ts      TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_permissions_code ON permissions (code);
CREATE INDEX idx_permissions_resource_action ON permissions (resource, action);

COMMENT ON TABLE permissions IS 'Атомарные разрешения. Формат code: RESOURCE:ACTION (например, USERS:READ)';
