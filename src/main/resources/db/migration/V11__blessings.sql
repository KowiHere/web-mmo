-- The only timed state until now was the knockout after death, and it is a
-- single column holding a moment: you wake at this instant, whether you were
-- playing or not, because a penalty you can sleep off is not a penalty.
--
-- A blessing is the opposite case. It was paid for, so its clock stops when
-- nobody is playing the character - which is why this table stores how much is
-- LEFT rather than when it ends. Writing the row is what stops the clock.
CREATE TABLE character_blessing (
    character_key VARCHAR(32) NOT NULL,
    def_id        VARCHAR(64) NOT NULL,
    remaining_ms  BIGINT      NOT NULL,
    CONSTRAINT pk_character_blessing PRIMARY KEY (character_key, def_id),
    CONSTRAINT fk_blessing_character FOREIGN KEY (character_key)
        REFERENCES game_character (name_key)
);
