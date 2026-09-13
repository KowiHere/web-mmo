-- Deliberately plain SQL: no vendor-specific types, no auto-generated schema.
-- The table is small enough to read, and keeping it portable means moving to
-- PostgreSQL later is a JDBC URL and a driver, not a rewrite.
CREATE TABLE game_character (
    name_key  VARCHAR(32) NOT NULL,
    name      VARCHAR(32) NOT NULL,
    map_id    VARCHAR(64) NOT NULL,
    x         INTEGER     NOT NULL,
    y         INTEGER     NOT NULL,
    dir       VARCHAR(8)  NOT NULL,
    last_seen TIMESTAMP   NOT NULL,
    CONSTRAINT pk_game_character PRIMARY KEY (name_key)
);
