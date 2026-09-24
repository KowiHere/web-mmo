-- Money, in the plural from the first day it exists.
--
-- Not a column called gold on game_character. There will be traders dealing in
-- something other than gold - that was decided before this was written - and a
-- column would have to be undone the day the second one appears, taking every
-- query that reads it along with it.
--
-- No UPDATE backfilling anybody: a missing row means none of that currency, and
-- every character alive today has earned nothing yet.
CREATE TABLE character_currency (
    character_key VARCHAR(32) NOT NULL,
    currency_id   VARCHAR(64) NOT NULL,
    amount        INTEGER     NOT NULL,
    CONSTRAINT pk_character_currency PRIMARY KEY (character_key, currency_id),
    CONSTRAINT fk_currency_character FOREIGN KEY (character_key)
        REFERENCES game_character (name_key)
);
