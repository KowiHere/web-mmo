-- Characters had statistics but nothing to make them out of, and killing a
-- creature paid nothing but experience. Attributes are what classes will
-- eventually differ in; items are the first thing that grants them.

-- Combat numbers are still not stored. What is stored is what they are computed
-- from: three attributes, and the points not yet spent on them.
ALTER TABLE game_character ADD COLUMN strength       INTEGER NOT NULL DEFAULT 5;
ALTER TABLE game_character ADD COLUMN agility        INTEGER NOT NULL DEFAULT 5;
ALTER TABLE game_character ADD COLUMN intellect      INTEGER NOT NULL DEFAULT 5;
ALTER TABLE game_character ADD COLUMN unspent_points INTEGER NOT NULL DEFAULT 0;

-- Everybody who already earned levels is handed the points those levels are
-- worth, rather than being quietly reset to a level-one character's power.
UPDATE game_character SET unspent_points = 3 * (level - 1) WHERE level > 1;

-- One row per actual item. What it is called, what it grants and what it
-- requires all live in the content files: storing them here would be a second
-- copy of the truth, and the two would disagree the first time an item is
-- rebalanced.
CREATE TABLE item_instance (
    id            VARCHAR(36) NOT NULL,
    character_key VARCHAR(32) NOT NULL,
    def_id        VARCHAR(64) NOT NULL,
    -- NULL means it is in the bag; a slot name means it is being worn.
    slot          VARCHAR(16),
    created_at    TIMESTAMP   NOT NULL,
    CONSTRAINT pk_item_instance PRIMARY KEY (id),
    CONSTRAINT fk_item_character FOREIGN KEY (character_key)
        REFERENCES game_character (name_key)
);

-- Everything reads items one character at a time, because that is how they are
-- loaded when somebody logs in and how they are rewritten when something moves.
CREATE INDEX ix_item_character ON item_instance (character_key);
