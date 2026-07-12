-- Demo credentials for this portfolio project only — see README.md and
-- docs/architecture.md ("Authentication and Authorization") for the
-- plaintext passwords these hashes correspond to. Not fit for any real
-- deployment: a production system would provision users through an
-- out-of-band admin flow, not a schema migration.
CREATE TABLE users (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    username       VARCHAR(64)  NOT NULL,
    password_hash  VARCHAR(255) NOT NULL,
    role           VARCHAR(32)  NOT NULL,
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_users_username UNIQUE (username),
    CONSTRAINT chk_users_role CHECK (role IN ('ADMIN', 'VIEWER'))
);

-- Seeded demo users. Passwords: admin / admin123, viewer / viewer123.
INSERT INTO users (username, password_hash, role) VALUES
    ('admin', '$2b$10$uDHN7955DUvgZrBr8OGgfesfJSLYEnPjERZ.svqVYKQmkC7yWJtxy', 'ADMIN'),
    ('viewer', '$2b$10$QSb16ipkG06dCbS7DPty3..9dRtiDgqcCR5aKgnTXZ3Vp/bzGrLQ2', 'VIEWER');
