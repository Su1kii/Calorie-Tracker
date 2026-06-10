CREATE TABLE refresh_tokens
(
    id         UUID      NOT NULL DEFAULT gen_random_uuid(),
    user_id    UUID      NOT NULL,
    token      TEXT      NOT NULL,
    expires_at TIMESTAMP NOT NULL,
    is_revoked BOOLEAN   NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),

    CONSTRAINT pk_refresh_tokens PRIMARY KEY (id),
    CONSTRAINT uq_refresh_tokens_token UNIQUE (token),
    CONSTRAINT fk_refresh_tokens_user
        FOREIGN KEY (user_id)
            REFERENCES users (id)
            ON DELETE CASCADE
);

CREATE INDEX idx_refresh_tokens_token
    ON refresh_tokens (token);

CREATE INDEX idx_refresh_tokens_user_id
    ON refresh_tokens (user_id);