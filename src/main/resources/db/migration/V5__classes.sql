-- Attributes gave characters something to be made of, but nothing said what
-- kind of character they were. A class decides which attribute a character's
-- blows are made of and how much of an opponent's armour they pass through, so
-- without it every character would want strength and the other two attributes
-- would be a tax.

-- Nullable on purpose: the world falls back to the default class when this is
-- empty or names a class the content no longer has. A character that cannot be
-- played because its class was renamed would be a worse outcome than one that
-- swings a sword instead of a staff until somebody notices.
ALTER TABLE game_character ADD COLUMN class_id VARCHAR(32);

-- Everybody who already exists fought with strength, because that is all there
-- was. The warrior is that character, so nobody is handed a different one.
UPDATE game_character SET class_id = 'wojownik' WHERE class_id IS NULL;
