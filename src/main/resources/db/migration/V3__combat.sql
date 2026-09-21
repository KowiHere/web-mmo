-- Characters could walk and talk but had nothing to fight with. These are the
-- only combat numbers stored: attack, armour and maximum health are derived
-- from the level, so there is no way for them to drift out of step with it.
ALTER TABLE game_character ADD COLUMN level INTEGER NOT NULL DEFAULT 1;
ALTER TABLE game_character ADD COLUMN xp BIGINT NOT NULL DEFAULT 0;

-- Current health, so logging out hurt means logging back in hurt. -1 stands for
-- "as healthy as this level allows", which is what every existing row is.
ALTER TABLE game_character ADD COLUMN hp INTEGER NOT NULL DEFAULT -1;

-- When the penalty for dying wears off. Stored rather than kept in memory,
-- because otherwise restarting the server would be a way of removing it.
ALTER TABLE game_character ADD COLUMN weakened_until TIMESTAMP;
