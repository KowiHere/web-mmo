-- Until now an item was its definition: two rusty swords were the same sword,
-- so one row per copy needed nothing but which definition it is. A bottle is
-- the first thing that breaks that - two bottles of the same brew differ by
-- exactly one number, and it is the number a player cares about.

-- NULL for everything that is not a bottle, which is everything else today.
ALTER TABLE item_instance ADD COLUMN remaining INTEGER;

-- Bottles get put away like anything else, and the account's chest is a
-- different table, so it needs the same column or a stored bottle would come
-- back full.
ALTER TABLE account_item ADD COLUMN remaining INTEGER;
