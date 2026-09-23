-- Classes decided how a character fights, but every character still fought by
-- swinging. Skills are what a class actually does, and energy is what pays for
-- them.

-- Energy is deliberately absent from this schema. It belongs to a fight rather
-- than to a character: it starts every fight at zero and is gone when the fight
-- ends, so a stored value would always read back as the only value it can have.
ALTER TABLE game_character ADD COLUMN skill_points INTEGER NOT NULL DEFAULT 0;

-- One point per level, including the first - so an existing character is owed
-- exactly its level, and a brand new one starts with a choice to make.
UPDATE game_character SET skill_points = level;

CREATE TABLE character_skill (
    character_key VARCHAR(32) NOT NULL,
    skill_id      VARCHAR(64) NOT NULL,
    rank          INTEGER     NOT NULL,
    CONSTRAINT pk_character_skill PRIMARY KEY (character_key, skill_id),
    CONSTRAINT fk_skill_character FOREIGN KEY (character_key)
        REFERENCES game_character (name_key)
);
