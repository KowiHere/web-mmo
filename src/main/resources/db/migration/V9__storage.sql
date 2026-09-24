-- A bag of twenty was the only container in the game, and a full one ends a
-- hunt: it turns every rare drop into a choice made under pressure. This is the
-- second container - one chest kept by a storekeeper, in tabs that can be
-- bought, plus a purse that stays in it.

-- An item in the chest is the same instance as an item in the bag, so it stays
-- in the same table. NULL means the bag or the character's back; a number means
-- that tab of the chest. Two tables would mean moving rows on every deposit,
-- and two places in which the same truth lives.
ALTER TABLE item_instance  ADD COLUMN tab SMALLINT;

-- How many tabs have been paid for. Everybody starts with the one that is free.
ALTER TABLE game_character ADD COLUMN storage_tabs SMALLINT NOT NULL DEFAULT 1;

-- The account's own chest cannot hang off character_key: it outlives any one
-- character and is shared by all of them. Hence its own table, keyed by the
-- account, and no slot column - nothing in a chest is ever worn.
CREATE TABLE account_item (
    id         VARCHAR(36) NOT NULL,
    account_id BIGINT      NOT NULL,
    def_id     VARCHAR(64) NOT NULL,
    tab        SMALLINT    NOT NULL,
    created_at TIMESTAMP   NOT NULL,
    CONSTRAINT pk_account_item PRIMARY KEY (id),
    CONSTRAINT fk_account_item_account FOREIGN KEY (account_id) REFERENCES account (id)
);

CREATE INDEX ix_account_item_account ON account_item (account_id);

-- A missing row means one tab, exactly as a missing currency row means no
-- money. Nothing is backfilled for anybody.
CREATE TABLE account_storage (
    account_id BIGINT   NOT NULL,
    tabs       SMALLINT NOT NULL DEFAULT 1,
    CONSTRAINT pk_account_storage PRIMARY KEY (account_id),
    CONSTRAINT fk_account_storage_account FOREIGN KEY (account_id) REFERENCES account (id)
);

-- Money left in the chest, in however many currencies - the same shape as
-- character_currency, and a separate table rather than a flag on it, because
-- what is in the chest and what is in hand are two different amounts that both
-- have to be asked about separately.
CREATE TABLE storage_currency (
    character_key VARCHAR(32) NOT NULL,
    currency_id   VARCHAR(64) NOT NULL,
    amount        INTEGER     NOT NULL,
    CONSTRAINT pk_storage_currency PRIMARY KEY (character_key, currency_id),
    CONSTRAINT fk_storage_currency_character FOREIGN KEY (character_key)
        REFERENCES game_character (name_key)
);

CREATE TABLE account_currency (
    account_id  BIGINT      NOT NULL,
    currency_id VARCHAR(64) NOT NULL,
    amount      INTEGER     NOT NULL,
    CONSTRAINT pk_account_currency PRIMARY KEY (account_id, currency_id),
    CONSTRAINT fk_account_currency_account FOREIGN KEY (account_id) REFERENCES account (id)
);
