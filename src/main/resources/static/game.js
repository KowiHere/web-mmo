'use strict';

/**
 * The client is a renderer and an input device. It owns no game truth: it draws
 * what the last delta said and asks the server for everything else. The only
 * things it invents are the motion *between* two server states and the moment
 * at which it chooses to show them.
 */

const TILE = 32;

/**
 * How far behind the newest known state other players are drawn.
 *
 * The server ticks perfectly evenly, but the network does not deliver evenly,
 * so playing each step the instant its frame lands makes smooth movement look
 * like stutter. Holding a small buffer lets late frames catch up inside it.
 *
 * Your own character is deliberately exempt - see enqueueStep.
 */
const INTERPOLATION_DELAY_MS = 150;

/** How often a held movement key repeats. */
const KEY_REPEAT_MS = 150;

/**
 * How close an ordinary creature must be before its name is drawn. A pack of
 * six wolves on adjacent tiles turns into a pile of overlapping labels, and the
 * name of the wolf two screens away was never the thing you needed to read.
 * Players and elites are always named.
 */
const NAME_RADIUS_TILES = 6;

const MARKER_LIFETIME_MS = 600;
const FLOATER_LIFETIME_MS = 1100;
const BUBBLE_LIFETIME_MS = 4500;

/**
 * How each kind of actor reads on the map. Players are blue-ish and named in
 * light text; creatures are warmer and sit lower, so a crowded tile is still
 * legible at a glance.
 */
const MOB_STYLE = {
    MOB: { fill: '#8a6a4a', stroke: 'rgba(0,0,0,.5)', label: '#c8a887', radius: 9, ring: null },
    ELITE: { fill: '#c9863f', stroke: '#f0c07a', label: '#f0c07a', radius: 11, ring: '#f0c07a' },
    HERO: { fill: '#b4558f', stroke: '#e79ac8', label: '#e79ac8', radius: 12, ring: '#e79ac8' },
    COLOSSUS: { fill: '#a03c3c', stroke: '#e58686', label: '#e58686', radius: 14, ring: '#e58686' },
};

/**
 * People and things. Drawn apart from both the other two: a creature is
 * something to click on and fight, and an NPC has to read as the opposite of
 * that before anybody clicks anything.
 */
const NPC_STYLE = {
    PERSON: { fill: '#4f8f74', stroke: '#9fd9bd', label: '#9fd9bd', radius: 10 },
    OBJECT: { fill: '#6b6250', stroke: '#c0b294', label: '#c0b294', radius: 8 },
};

const MOVEMENT_KEYS = {
    ArrowUp: [0, -1], w: [0, -1], W: [0, -1],
    ArrowDown: [0, 1], s: [0, 1], S: [0, 1],
    ArrowLeft: [-1, 0], a: [-1, 0], A: [-1, 0],
    ArrowRight: [1, 0], d: [1, 0], D: [1, 0],
};

const state = {
    ws: null,
    characterKey: null,
    leaving: false,
    /** Who we are walking over to talk to, until we get there. */
    walkingUpTo: null,
    /** The last thing said to us, or null when nobody is talking. */
    dialogue: null,
    /** What there is to spend, by currency. */
    purse: null,
    /** The stall that is open, or null. */
    shop: null,
    storage: null,
    // Which tab of each chest is being looked at. The client's own business:
    // the server sends every tab at once and has no idea one is on top.
    openTab: { character: 0, account: 0 },
    version: 0,
    selfId: null,
    map: null,
    actors: new Map(),
    reconnectDelay: 1000,
    everConnected: false,
    hover: null,
    marker: null,
    debug: false,
    held: new Set(),
    lastKeyMoveAt: 0,
    you: null,
    bag: null,
    skills: null,
    floaters: [],
    stats: { frames: 0, fps: 0, deltas: 0, deltaRate: 0, sampledAt: 0 },
};

const canvas = document.getElementById('view');
const ctx = canvas.getContext('2d');
const statusEl = document.getElementById('status');
const mapNameEl = document.getElementById('map-name');
const chatLog = document.getElementById('chat-log');
const chatInput = document.getElementById('chat-input');
const debugEl = document.getElementById('debug');
const sheetEl = document.getElementById('sheet');
const fleeButton = document.getElementById('flee');
const panelEl = document.getElementById('panel');
const panelToggle = document.getElementById('panel-toggle');
const attributesEl = document.getElementById('attributes');
const pointsEl = document.getElementById('points');
const equipmentEl = document.getElementById('equipment');
const bagEl = document.getElementById('bag');
const bagCountEl = document.getElementById('bag-count');
const skillsEl = document.getElementById('skills');
const skillPointsEl = document.getElementById('skill-points');
const skillbarEl = document.getElementById('skillbar');
const purseEl = document.getElementById('purse');
const knockoutEl = document.getElementById('knockout');
const knockoutCountEl = document.getElementById('knockout-count');
const storageEl = document.getElementById('storage');
const storageWhoEl = document.getElementById('storage-who');
const storageChestsEl = document.getElementById('storage-chests');
const shopEl = document.getElementById('shop');
const shopWhoEl = document.getElementById('shop-who');
const shopGoodsEl = document.getElementById('shop-goods');
const shopSellEl = document.getElementById('shop-sell');
const shopSellTitleEl = document.getElementById('shop-sell-title');
const dialogueEl = document.getElementById('dialogue');
const dialogueWhoEl = document.getElementById('dialogue-who');
const dialogueTextEl = document.getElementById('dialogue-text');
const dialogueOptionsEl = document.getElementById('dialogue-options');

// ---------------------------------------------------------------- networking

/**
 * Opens the world as one character.
 *
 * The socket carries no claim about who we are: the session cookie and the
 * character name go through the handshake, where the server decides. A socket
 * that is not ours is never created, so there is nothing to prove afterwards.
 */
function connect(characterKey) {
    state.characterKey = characterKey;
    state.leaving = false;

    const proto = location.protocol === 'https:' ? 'wss:' : 'ws:';
    const ws = new WebSocket(`${proto}//${location.host}/ws?character=${encodeURIComponent(characterKey)}`);
    state.ws = ws;
    setStatus('łączenie…', 'down');

    let opened = false;

    ws.onopen = () => {
        opened = true;
        state.reconnectDelay = 1000;
        // `since` asks for the frames we missed; identity is already settled.
        ws.send(JSON.stringify({ type: 'hello', since: state.version }));
    };

    ws.onmessage = (event) => {
        const msg = JSON.parse(event.data);
        if (msg.type === 'init') applyInit(msg);
        else if (msg.type === 'delta') applyDelta(msg);
        else if (msg.type === 'you') applyYou(msg);
        else if (msg.type === 'bag') applyBag(msg);
        else if (msg.type === 'skills') applySkills(msg);
        else if (msg.type === 'dialogue') applyDialogue(msg);
        else if (msg.type === 'purse') applyPurse(msg);
        else if (msg.type === 'shop') applyShop(msg);
        else if (msg.type === 'storage') applyStorage(msg);
        else if (msg.type === 'error') logSystem(msg.message);
    };

    ws.onclose = () => {
        if (state.leaving) return;
        if (!opened) {
            // The handshake itself was refused - an expired session, or a
            // character that is not ours. Retrying cannot fix either, so go back
            // and let the selection screen find out which it was.
            returnToSelection();
            return;
        }
        setStatus('rozłączono, ponawiam…', 'down');
        setTimeout(() => connect(characterKey), state.reconnectDelay);
        state.reconnectDelay = Math.min(state.reconnectDelay * 2, 10000);
    };

    ws.onerror = () => ws.close();
}

function send(message) {
    if (state.ws && state.ws.readyState === WebSocket.OPEN) {
        state.ws.send(JSON.stringify(message));
    }
}

// ------------------------------------------------------------- state updates

function applyInit(msg) {
    const arrivedSomewhereElse = state.map && state.map.id !== msg.map.id;

    state.version = msg.v;
    state.selfId = msg.selfId;
    state.map = msg.map;

    state.actors.clear();
    // Everything drawn about the last map goes with it. A damage number left
    // over from the wood would float over the glade, pinned to a tile that
    // means something else now.
    state.floaters.length = 0;
    state.marker = null;
    state.walkingUpTo = null;
    applyDialogue({});
    applyShop({});
    applyStorage({});

    for (const dto of msg.actors || []) upsertActor(dto);

    mapNameEl.textContent = msg.map.name;
    setStatus('połączono');
    if (arrivedSomewhereElse) logSystem(`Wchodzisz: ${msg.map.name}.`);
    else if (state.everConnected) logSystem('Wczytano świat od nowa.');
    state.everConnected = true;
}

function applyDelta(msg) {
    // A gap means we missed frames and our picture is no longer trustworthy.
    // Reloading is the honest response; drifting quietly is not.
    if (msg.v !== state.version + 1 && state.version !== 0) {
        location.reload();
        return;
    }
    state.version = msg.v;
    state.stats.deltas++;
    setStatus('połączono');

    const now = performance.now();

    for (const mv of msg.moved || []) {
        const actor = state.actors.get(mv.id);
        if (!actor) continue;
        actor.x = mv.x;
        actor.y = mv.y;
        actor.dir = mv.dir;
        enqueueStep(actor, mv, now);
    }

    for (const p of msg.presence || []) {
        const actor = state.actors.get(p.id);
        if (!actor) continue;
        actor.online = p.online;
        if (actor.kind !== 'MOB') {
            logSystem(`${actor.name} ${p.online ? 'wrócił do gry' : 'stracił połączenie'}.`);
        }
    }

    for (const blow of msg.damage || []) {
        const target = state.actors.get(blow.target);
        if (!target) continue;
        target.hp = blow.hp;
        state.floaters.push({
            // Where the blow landed. Held here rather than looked up later,
            // because by then the target may be dead, or back at the spawn.
            x: target.rx ?? target.x,
            y: target.ry ?? target.y,
            text: `-${blow.amount}`,
            colour: blow.target === state.selfId ? '#e06c75' : '#f0c07a',
            until: now + FLOATER_LIFETIME_MS,
            born: now,
        });
    }

    for (const id of msg.died || []) {
        const actor = state.actors.get(id);
        if (!actor) continue;
        // A creature that dies is gone from the world - the server has already
        // removed it. A player is not: killPlayer re-announced them in this
        // same delta, back at the spawn with full health, and that arrived a
        // few lines above. Zeroing their health here would undo it.
        if (actor.kind === 'MOB') state.actors.delete(id);
    }

    for (const change of msg.fights || []) {
        const actor = state.actors.get(change.id);
        if (actor) actor.inFight = change.inFight;
        if (change.id === state.selfId) {
            updateFleeButton();
            renderSkillBar();
        }
    }

    // Arrivals and departures last, on purpose. A delta says what happened and
    // then what the world looks like now, and death is both at once: the fatal
    // blow and the re-announcement of a character already back at the spawn
    // with full health travel together. Applying the blow afterwards would
    // leave the living character showing an empty health bar.
    for (const dto of msg.joined || []) upsertActor(dto);
    for (const id of msg.left || []) state.actors.delete(id);

    for (const line of msg.chat || []) {
        const actor = state.actors.get(line.id);
        if (actor) actor.bubble = { text: line.text, until: now + BUBBLE_LIFETIME_MS };
        logChat(line.name, line.text);
    }
}

/** The player's own sheet, which only ever arrives addressed to them. */
function applyYou(msg) {
    const wasLevel = state.you && state.you.level;
    state.you = msg;
    if (wasLevel && msg.level > wasLevel) logSystem(`Awans na poziom ${msg.level}!`);
    renderSheet();
    renderPanel();
    renderSkillBar();
    updateFleeButton();
}

function applyPurse(msg) {
    state.purse = msg;
    renderPurse();
    renderShop();
    renderStorage();
    renderPanel();
}

/** A stall with no goods is one that has closed, the same as an empty dialogue. */
function applyShop(msg) {
    state.shop = msg.goods ? msg : null;
    renderShop();
}

/** The money the game itself charges in, as the server labelled it. */
function primaryCoin() {
    return (state.purse ? state.purse.coins || [] : []).find((c) => c.primary) || null;
}

function amountOf(currencyId) {
    const coin = (state.purse ? state.purse.coins || [] : [])
        .find((c) => c.id === currencyId);
    return coin ? coin.amount : 0;
}

function renderPurse() {
    purseEl.innerHTML = '';
    for (const coin of state.purse ? state.purse.coins || [] : []) {
        const row = document.createElement('li');
        const name = document.createElement('span');
        name.textContent = coin.name;
        const amount = document.createElement('span');
        amount.className = 'coin-amount';
        amount.textContent = `${coin.amount} ${coin.shortName}`;
        row.append(name, amount);
        purseEl.append(row);
    }
}

function renderShop() {
    if (!state.shop) {
        shopEl.hidden = true;
        return;
    }
    const shop = state.shop;
    const held = amountOf(shop.currencyId);
    shopWhoEl.textContent = `${shop.name} — masz ${held} ${shop.currencyShort}`;
    // Short on purpose: the rule is explained by the empty state below, which
    // is where somebody looks when the column is not offering them anything.
    shopSellTitleEl.textContent = 'Sprzedaż';

    shopGoodsEl.innerHTML = '';
    for (const goods of shop.goods || []) {
        const row = tradeRow(goods.name, `${goods.price} ${shop.currencyShort}`);
        if (goods.price > held) {
            row.classList.add('too-dear');
            row.title = `Za mało: ${goods.price} ${shop.currencyShort}`;
        } else {
            row.title = `Kup za ${goods.price} ${shop.currencyShort}`;
            row.addEventListener('click', () => send({ type: 'buy', itemId: goods.defId }));
        }
        shopGoodsEl.append(row);
    }

    // What is in the bag and on this trader's shelf. Worn things are absent on
    // purpose: the server refuses them, and offering the click anyway would be
    // the interface promising something the rules do not allow.
    const sellable = (state.bag ? state.bag.carried || [] : [])
        .map((item) => [item, (shop.goods || []).find((g) => g.defId === item.defId)])
        .filter(([, goods]) => goods);
    shopSellEl.innerHTML = '';
    if (!sellable.length) {
        const empty = document.createElement('li');
        empty.className = 'empty';
        empty.textContent = `${shop.name} skupuje tylko to, czym sam handluje`;
        shopSellEl.append(empty);
    }
    for (const [item, goods] of sellable) {
        const row = tradeRow(item.name, `+${goods.buyback} ${shop.currencyShort}`);
        row.title = `Sprzedaj za ${goods.buyback} ${shop.currencyShort}`;
        row.addEventListener('click', () => send({ type: 'sell', itemId: item.id }));
        shopSellEl.append(row);
    }
    shopEl.hidden = false;
}

/** No chests means the window has closed, the same as an empty stall. */
function applyStorage(msg) {
    state.storage = msg.chests ? msg : null;
    renderStorage();
}

function renderStorage() {
    if (!state.storage) {
        storageEl.hidden = true;
        return;
    }
    storageWhoEl.textContent = state.storage.name;
    storageChestsEl.innerHTML = '';
    for (const chest of state.storage.chests || []) {
        storageChestsEl.append(chestBlock(chest));
    }
    storageEl.hidden = false;
}

function chestBlock(chest) {
    const shared = chest.scope === 'account';
    const block = document.createElement('div');
    block.className = shared ? 'chest shared' : 'chest';

    const title = document.createElement('h3');
    title.textContent = shared
        ? 'Skład konta — wspólny dla wszystkich twoich postaci'
        : 'Skład postaci';
    block.append(title);

    // Clamped, because a tab that was open a moment ago can stop existing when
    // the same account is looked at from a character with fewer of them.
    const open = Math.min(state.openTab[chest.scope] || 0, chest.tabs - 1);
    state.openTab[chest.scope] = open;

    const tabs = document.createElement('div');
    tabs.className = 'chest-tabs';
    for (let i = 0; i < chest.tabs; i++) {
        const button = document.createElement('button');
        button.textContent = `${i + 1}`;
        if (i === open) button.classList.add('on');
        button.addEventListener('click', () => {
            state.openTab[chest.scope] = i;
            renderStorage();
        });
        tabs.append(button);
    }
    if (chest.nextTab >= 0) {
        const buy = document.createElement('button');
        buy.className = 'buy';
        buy.textContent = `+ ${chest.nextTab} ${chest.currencyShort}`;
        buy.title = `Wynajmij następną zakładkę za ${chest.nextTab} ${chest.currencyShort}`;
        buy.addEventListener('click', () => send({ type: 'buyTab', account: shared }));
        tabs.append(buy);
    }
    block.append(tabs);

    const cols = document.createElement('div');
    cols.className = 'chest-cols';

    const stored = document.createElement('div');
    const storedTitle = document.createElement('h4');
    storedTitle.textContent = `Zakładka ${open + 1}`;
    const list = document.createElement('ul');
    list.className = 'slots';
    const inTab = (chest.items || []).filter((kept) => kept.tab === open);
    if (!inTab.length) {
        const empty = document.createElement('li');
        empty.className = 'empty';
        empty.textContent = 'Pusto — kliknij rzecz w plecaku, żeby ją tu odłożyć';
        list.append(empty);
    }
    for (const kept of inTab) {
        const row = tradeRow(kept.item.name, 'wyjmij');
        row.title = 'Wyjmij do plecaka';
        row.addEventListener('click', () => send({
            type: 'withdraw', itemId: kept.item.id, account: shared,
        }));
        list.append(row);
    }
    stored.append(storedTitle, list);

    // Only what is in the bag: the server refuses to take anything off the
    // character's back, so offering the click would be a promise it breaks.
    const carried = state.bag ? state.bag.carried || [] : [];
    const hand = document.createElement('div');
    const handTitle = document.createElement('h4');
    handTitle.textContent = 'W plecaku';
    const put = document.createElement('ul');
    put.className = 'slots';
    if (!carried.length) {
        const empty = document.createElement('li');
        empty.className = 'empty';
        empty.textContent = 'Plecak pusty';
        put.append(empty);
    }
    for (const item of carried) {
        const row = tradeRow(item.name, 'odłóż');
        row.title = `Odłóż do zakładki ${open + 1}`;
        row.addEventListener('click', () => send({
            type: 'deposit', itemId: item.id, tab: open, account: shared,
        }));
        put.append(row);
    }
    hand.append(handTitle, put);
    cols.append(stored, hand);
    block.append(cols);

    const coin = (chest.coins || []).find((c) => c.primary);
    if (coin) {
        const money = document.createElement('div');
        money.className = 'chest-money';
        const label = document.createElement('span');
        label.textContent = `W składzie: ${coin.amount} ${coin.shortName}`;
        const inHand = document.createElement('span');
        const deposit = document.createElement('button');
        deposit.textContent = 'odłóż 100';
        deposit.addEventListener('click', () => send({
            type: 'depositCoins', currencyId: coin.id, amount: 100, account: shared,
        }));
        const withdraw = document.createElement('button');
        withdraw.textContent = 'wyjmij 100';
        withdraw.addEventListener('click', () => send({
            type: 'withdrawCoins', currencyId: coin.id, amount: 100, account: shared,
        }));
        inHand.append(deposit, withdraw);
        money.append(label, inHand);
        block.append(money);
    }
    return block;
}

function tradeRow(name, price) {
    const row = document.createElement('li');
    const label = document.createElement('span');
    label.textContent = name;
    const tag = document.createElement('span');
    tag.className = 'price';
    tag.textContent = price;
    row.append(label, tag);
    return row;
}

function applyBag(msg) {
    state.bag = msg;
    renderShop();
    renderStorage();
    renderPanel();
}

/**
 * What somebody is saying. A frame with no text is the conversation ending -
 * which happens by walking away at least as often as by saying goodbye, so the
 * window has to be able to close without anybody having pressed anything.
 */
function applyDialogue(msg) {
    if (!msg.text) {
        state.dialogue = null;
        dialogueEl.hidden = true;
        dialogueOptionsEl.replaceChildren();
        return;
    }
    state.dialogue = msg;
    dialogueWhoEl.textContent = msg.name;
    dialogueTextEl.textContent = msg.text;

    const buttons = (msg.options || []).map((option) => {
        const button = document.createElement('button');
        button.type = 'button';
        button.textContent = option.text;
        // The index the server sent, handed straight back. The client answers
        // the question it was asked and cannot invent a different one.
        button.addEventListener('click', () => send({ type: 'choose', option: option.index }));
        return button;
    });
    dialogueOptionsEl.replaceChildren(...buttons);
    dialogueEl.hidden = false;
}

function applySkills(msg) {
    state.skills = msg;
    renderPanel();
    renderSkillBar();
}

function renderSheet() {
    if (!state.you) return;
    const you = state.you;
    const healthPercent = Math.max(0, Math.round((you.hp / you.maxHp) * 100));
    const xpPercent = you.xpForNextLevel > 0
        ? Math.max(0, Math.min(100, Math.round((you.xpThisLevel / you.xpForNextLevel) * 100)))
        : 0;
    const out = you.wakesAt > Date.now();

    sheetEl.innerHTML = '';
    sheetEl.append(
        bar('hp', `${you.hp} / ${you.maxHp}`, healthPercent),
        // Energy belongs to the fight: empty outside one, filling inside. That
        // it moves at all is the whole difference from the mana it replaced.
        bar('energy', `${you.energy} / ${you.maxEnergy}`,
            you.maxEnergy > 0 ? (you.energy / you.maxEnergy) * 100 : 0),
        bar('xp', `poziom ${you.level}`, xpPercent),
    );
    if (out) {
        const note = document.createElement('div');
        note.className = 'knocked-out';
        note.textContent = `ocknienie za: ${Math.ceil((you.wakesAt - Date.now()) / 1000)} s`;
        sheetEl.append(note);
    }
    renderKnockout(out ? you.wakesAt : 0);
}

/**
 * The overlay a knocked out character waits behind.
 *
 * Deliberately not a modal: the world carries on underneath and the chat box
 * still works, because the character is unconscious and the person is not.
 */
function renderKnockout(wakesAt) {
    if (!wakesAt) {
        knockoutEl.hidden = true;
        return;
    }
    knockoutCountEl.textContent = `${Math.ceil((wakesAt - Date.now()) / 1000)} s`;
    knockoutEl.hidden = false;
}

function bar(kind, label, percent) {
    const wrap = document.createElement('div');
    wrap.className = `bar bar-${kind}`;
    const fill = document.createElement('div');
    fill.className = 'bar-fill';
    fill.style.width = `${percent}%`;
    const text = document.createElement('span');
    text.className = 'bar-label';
    text.textContent = label;
    wrap.append(fill, text);
    return wrap;
}

const ATTRIBUTE_NAMES = {
    STRENGTH: 'Siła',
    AGILITY: 'Zwinność',
    INTELLECT: 'Inteligencja',
};

const SLOT_NAMES = {
    WEAPON: 'Broń',
    CHEST: 'Tors',
    TRINKET: 'Ozdoba',
};

const SLOT_ORDER = ['WEAPON', 'CHEST', 'TRINKET'];

function renderPanel() {
    if (panelEl.hidden) return;
    renderAttributes();
    renderSkills();
    renderEquipment();
    renderBag();
}

function renderAttributes() {
    const you = state.you;
    attributesEl.innerHTML = '';
    if (!you) return;

    const values = { STRENGTH: you.strength, AGILITY: you.agility, INTELLECT: you.intellect };
    for (const [key, label] of Object.entries(ATTRIBUTE_NAMES)) {
        const row = document.createElement('li');
        const name = document.createElement('span');
        name.textContent = label;
        const value = document.createElement('span');
        value.className = 'value';
        value.textContent = values[key];
        row.append(name, value);

        if (you.unspentPoints > 0) {
            const spend = document.createElement('button');
            spend.type = 'button';
            spend.textContent = '+';
            spend.title = `Wydaj punkt na: ${label}`;
            spend.addEventListener('click', () => send({ type: 'spend', attribute: key }));
            row.append(spend);
        }
        attributesEl.append(row);
    }

    // The derived numbers, so it is obvious what a point actually bought.
    for (const [label, text] of [
        ['Atak', you.attack],
        ['Pancerz', you.armor],
        ['Unik', `${you.dodgePercent}%`],
        ['Drugi cios', `${you.secondBlowPercent}%`],
    ]) {
        const row = document.createElement('li');
        const name = document.createElement('span');
        name.className = 'slot-name';
        name.textContent = label;
        const value = document.createElement('span');
        value.className = 'value';
        value.textContent = text;
        row.append(name, value);
        attributesEl.append(row);
    }

    if (you.className) {
        const row = document.createElement('li');
        const name = document.createElement('span');
        name.className = 'slot-name';
        name.textContent = 'Klasa';
        const value = document.createElement('span');
        value.className = 'value';
        value.textContent = you.className;
        row.append(name, value);
        attributesEl.prepend(row);
    }

    pointsEl.hidden = you.unspentPoints <= 0;
    pointsEl.textContent = `Punkty do rozdania: ${you.unspentPoints}`;
}

function renderEquipment() {
    equipmentEl.innerHTML = '';
    const worn = new Map((state.bag ? state.bag.worn || [] : []).map((item) => [item.slot, item]));

    for (const slot of SLOT_ORDER) {
        const item = worn.get(slot);
        if (!item) {
            const empty = document.createElement('li');
            empty.className = 'empty';
            empty.innerHTML = `<span class="slot-name">${SLOT_NAMES[slot]}</span>`;
            equipmentEl.append(empty);
            continue;
        }
        equipmentEl.append(itemRow(item, () => send({ type: 'unequip', slot }),
            `Zdejmij: ${item.name}`));
    }
}

function renderSkills() {
    skillsEl.innerHTML = '';
    const known = state.skills ? state.skills.skills || [] : [];
    const points = state.skills ? state.skills.skillPoints : 0;
    // The price rides in "you" rather than here, because it changes on walking
    // up to your master while this frame goes out a few times an hour.
    const price = state.you ? state.you.skillPointPrice || 0 : 0;
    const money = primaryCoin();
    skillPointsEl.textContent = points > 0
        ? `${points} do rozdania · ${price} ${money ? money.shortName : ''}`
        : '';

    if (!known.length) {
        const empty = document.createElement('li');
        empty.className = 'empty';
        empty.textContent = 'Brak';
        skillsEl.append(empty);
        return;
    }

    for (const skill of known) {
        const row = document.createElement('li');
        row.className = skill.rank > 0 ? 'known' : '';
        row.title = skill.description || '';
        row.dataset.skillId = skill.id;

        const name = document.createElement('span');
        name.textContent = skill.name;
        const rank = document.createElement('span');
        rank.className = 'rank';
        // A passive says what it is rather than what it costs, because "0
        // energy" reads as free rather than as "never used".
        rank.textContent = skill.passive
            ? `${skill.rank}/${skill.maxRank} · stale`
            : `${skill.rank}/${skill.maxRank} · ${skill.cost} en.`;
        row.append(name, rank);

        if (points > 0 && skill.rank < skill.maxRank) {
            // The first rank of anything is free, so the button says so rather
            // than quoting a price the server would not charge.
            const due = skill.rank === 0 ? 0 : price;
            const held = money ? money.amount : 0;
            const raise = document.createElement('button');
            raise.type = 'button';
            raise.textContent = '+';
            if (due > held) {
                raise.disabled = true;
                raise.title = `Za mało: ${due} ${money ? money.shortName : ''}`
                    + ' — u mistrza swojej klasy zapłacisz połowę';
            } else {
                raise.title = due === 0
                    ? `Rozwiń: ${skill.name} — pierwsza ranga za darmo`
                    : `Rozwiń: ${skill.name} za ${due} ${money ? money.shortName : ''}`;
                raise.addEventListener('click', () => send({ type: 'learn', skillId: skill.id }));
            }
            row.append(raise);
        }
        skillsEl.append(row);
    }
}

/**
 * The buttons pressed during a round.
 *
 * Only what is both learned and usable appears at all, and what cannot be paid
 * for yet is greyed rather than removed: a button that disappears the moment it
 * becomes unaffordable is one that moves under the cursor mid-fight.
 */
function renderSkillBar() {
    const self = state.actors.get(state.selfId);
    const fighting = !!(self && self.inFight);
    const usable = (state.skills ? state.skills.skills || [] : [])
        .filter((skill) => skill.rank > 0 && !skill.passive);

    skillbarEl.hidden = !fighting || !usable.length;
    if (skillbarEl.hidden) {
        skillbarEl.innerHTML = '';
        skillbarEl.dataset.showing = '';
        return;
    }

    // Rebuilt only when the set of skills changes, which is a few times an
    // hour. Rebuilding on every "you" - and one of those arrives every round -
    // would replace the buttons under the player's cursor mid-fight, which is
    // exactly when they are being aimed at.
    const showing = usable.map((skill) => skill.id).join(',');
    if (skillbarEl.dataset.showing !== showing) {
        skillbarEl.dataset.showing = showing;
        skillbarEl.innerHTML = '';
        for (const skill of usable) {
            const button = document.createElement('button');
            button.type = 'button';
            button.dataset.skillId = skill.id;
            button.title = skill.description || '';

            const name = document.createElement('span');
            name.textContent = skill.name;
            const cost = document.createElement('span');
            cost.className = 'cost';
            cost.textContent = `${skill.cost}`;
            button.append(name, cost);
            button.addEventListener('click', () => send({ type: 'use', skillId: skill.id }));
            skillbarEl.append(button);
        }
    }

    // What does change every round is whether each one can be paid for. Worked
    // out here rather than sent: the cost is fixed and the energy arrives in
    // every "you", so a flag from the server would be stale on arrival.
    for (const button of skillbarEl.children) {
        const skill = usable.find((candidate) => candidate.id === button.dataset.skillId);
        button.disabled = !skill || !state.you || state.you.energy < skill.cost;
    }
}

function renderBag() {
    bagEl.innerHTML = '';
    const carried = state.bag ? state.bag.carried || [] : [];
    bagCountEl.textContent = state.bag ? `${carried.length} / ${state.bag.capacity}` : '';

    if (!carried.length) {
        const empty = document.createElement('li');
        empty.className = 'empty';
        empty.textContent = 'Pusto';
        bagEl.append(empty);
        return;
    }
    for (const item of carried) {
        bagEl.append(itemRow(item,
            item.wearable ? () => send({ type: 'equip', itemId: item.id }) : null,
            item.wearable ? `Załóż: ${item.name}` : `Wymaga poziomu ${item.requiresLevel}`));
    }
}

function itemRow(item, onClick, title) {
    const row = document.createElement('li');
    row.title = title;
    row.dataset.itemId = item.id;
    row.dataset.defId = item.defId;
    if (!onClick) row.classList.add('locked');

    const slot = document.createElement('span');
    slot.className = 'slot-name';
    slot.textContent = SLOT_NAMES[item.slot] || item.slot;
    const name = document.createElement('span');
    name.textContent = item.name;
    const bonus = document.createElement('span');
    bonus.className = 'bonus';
    bonus.textContent = describeBonuses(item);

    row.append(slot, name, bonus);
    if (onClick) row.addEventListener('click', onClick);
    return row;
}

/** Everything an item grants, short enough to sit on one line. */
function describeBonuses(item) {
    const parts = [];
    if (item.attack) parts.push(`+${item.attack} atk`);
    if (item.armor) parts.push(`+${item.armor} panc`);
    if (item.strength) parts.push(`+${item.strength} sił`);
    if (item.agility) parts.push(`+${item.agility} zwn`);
    if (item.intellect) parts.push(`+${item.intellect} int`);
    return parts.join(' ');
}

function togglePanel(show) {
    panelEl.hidden = show === undefined ? !panelEl.hidden : !show;
    panelToggle.textContent = panelEl.hidden ? 'Postać (i)' : 'Zamknij (i)';
    renderPanel();
}

function updateFleeButton() {
    const self = state.actors.get(state.selfId);
    fleeButton.hidden = !(self && self.inFight);
}

function upsertActor(dto) {
    const existing = state.actors.get(dto.id);
    if (existing) {
        Object.assign(existing, dto);
        // An actor already here that is announced again did not walk - it was
        // put somewhere, which today means it died and woke at the spawn. Drop
        // whatever was still being played out, or the body slides across the
        // whole map to get there.
        existing.steps = [];
        existing.anim = null;
        existing.rx = dto.x;
        existing.ry = dto.y;
        return;
    }
    state.actors.set(dto.id, {
        ...dto,
        rx: dto.x,
        ry: dto.y,
        steps: [],
        anim: null,
        bubble: null,
    });
}

/**
 * Queues one confirmed step for playback.
 *
 * Other players are held back by INTERPOLATION_DELAY_MS so uneven delivery has
 * somewhere to hide. Your own character is not: without client-side prediction
 * it already waits for the server to agree, and adding the buffer on top would
 * make your own movement feel heavy for no gain in smoothness.
 *
 * Steps chain off the previous one so a burst of late frames plays out in order
 * at the right pace instead of all at once.
 */
function enqueueStep(actor, mv, now) {
    const delay = actor.id === state.selfId ? 0 : INTERPOLATION_DELAY_MS;
    const previous = actor.steps.length ? actor.steps[actor.steps.length - 1] : actor.anim;
    const startAt = previous
        ? Math.max(now + delay, previous.startAt + previous.ms)
        : now + delay;

    actor.steps.push({ fx: mv.fx, fy: mv.fy, tx: mv.x, ty: mv.y, ms: mv.ms, startAt });
}

// ------------------------------------------------------------------ renderer

function resize() {
    const dpr = window.devicePixelRatio || 1;
    canvas.width = Math.floor(canvas.clientWidth * dpr);
    canvas.height = Math.floor(canvas.clientHeight * dpr);
    ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
}

window.addEventListener('resize', resize);

/** Where the camera's top-left corner sits, in world pixels. */
function cameraOrigin() {
    const viewW = canvas.clientWidth;
    const viewH = canvas.clientHeight;
    const worldW = state.map.width * TILE;
    const worldH = state.map.height * TILE;

    const self = state.actors.get(state.selfId);
    const cx = self ? (self.rx + 0.5) * TILE : worldW / 2;
    const cy = self ? (self.ry + 0.5) * TILE : worldH / 2;

    // A map smaller than the viewport is centred rather than clamped to a corner.
    const ox = worldW <= viewW ? (worldW - viewW) / 2 : clamp(cx - viewW / 2, 0, worldW - viewW);
    const oy = worldH <= viewH ? (worldH - viewH) / 2 : clamp(cy - viewH / 2, 0, worldH - viewH);
    return { ox, oy };
}

function clamp(value, min, max) {
    return Math.max(min, Math.min(max, value));
}

function isBlocked(x, y) {
    if (!state.map) return true;
    if (x < 0 || y < 0 || x >= state.map.width || y >= state.map.height) return true;
    return state.map.collision[y][x] === '#';
}

function frame(now) {
    requestAnimationFrame(frame);
    if (!state.map) return;

    repeatHeldMovement(now);
    askWhenWeGetThere();
    for (const actor of state.actors.values()) advanceAnimation(actor, now);

    const { ox, oy } = cameraOrigin();
    ctx.fillStyle = '#0f1116';
    ctx.fillRect(0, 0, canvas.clientWidth, canvas.clientHeight);

    drawTiles(ox, oy);
    drawDoors(ox, oy);
    drawHover(ox, oy);
    drawMarker(ox, oy, now);

    // Painter's order: whoever is further down the map is drawn last, so an
    // actor in front correctly overlaps one behind - except yourself, who is
    // drawn last of all. Creatures and players share tiles, and losing your own
    // character under a wolf is never the right answer.
    const actors = [...state.actors.values()].sort((a, b) => {
        if (a.id === state.selfId) return 1;
        if (b.id === state.selfId) return -1;
        return a.ry - b.ry;
    });
    for (const actor of actors) drawActor(actor, ox, oy, now);
    drawFloaters(ox, oy, now);

    sampleStats(now);
}

/**
 * Clicking somebody means "go and talk to them", the same way clicking a
 * creature means "go and fight it". The difference is that the server walks you
 * to a creature itself, and a conversation is asked for on arrival - so this
 * waits until we are actually standing next to them before asking.
 */
function askWhenWeGetThere() {
    if (state.walkingUpTo === null) return;
    const self = state.actors.get(state.selfId);
    const them = state.actors.get(state.walkingUpTo);
    if (!self || !them) {
        state.walkingUpTo = null;
        return;
    }
    const distance = Math.max(Math.abs(self.x - them.x), Math.abs(self.y - them.y));
    if (distance > 1) return;
    send({ type: 'talk', npcId: them.id });
    state.walkingUpTo = null;
}

function advanceAnimation(actor, now) {
    if (!actor.anim && actor.steps.length && now >= actor.steps[0].startAt) {
        actor.anim = actor.steps.shift();
    }

    const step = actor.anim;
    if (!step || now < step.startAt) {
        actor.walk = 0;
        return;
    }

    const t = clamp((now - step.startAt) / step.ms, 0, 1);
    actor.rx = step.fx + (step.tx - step.fx) * t;
    actor.ry = step.fy + (step.ty - step.fy) * t;
    // Peaks mid-step and returns to zero, so the body reads as taking a stride.
    actor.walk = Math.sin(t * Math.PI);

    if (t >= 1) {
        actor.rx = step.tx;
        actor.ry = step.ty;
        actor.anim = null;
        actor.walk = 0;
    }
}

/**
 * The way out, on the tiles that are one.
 *
 * <p>Every door is drawn the same, because whether this particular character
 * may use it is the server's business: one map description is shared by
 * everybody standing on the map, and a threshold is about the one asking.
 */
function drawDoors(ox, oy) {
    for (const door of (state.map.doors || [])) {
        const sx = Math.round(door.x * TILE - ox);
        const sy = Math.round(door.y * TILE - oy);

        ctx.fillStyle = '#2c2119';
        ctx.fillRect(sx + 6, sy + 3, TILE - 12, TILE - 5);
        ctx.strokeStyle = '#c8a24a';
        ctx.lineWidth = 2;
        ctx.strokeRect(sx + 6, sy + 3, TILE - 12, TILE - 5);
        // A handle, so it reads as a door rather than as a crate.
        ctx.beginPath();
        ctx.arc(sx + TILE - 11, sy + TILE / 2 + 1, 1.8, 0, Math.PI * 2);
        ctx.fillStyle = '#e8c87a';
        ctx.fill();
    }
}

function drawTiles(ox, oy) {
    const firstX = Math.max(0, Math.floor(ox / TILE));
    const firstY = Math.max(0, Math.floor(oy / TILE));
    const lastX = Math.min(state.map.width - 1, Math.ceil((ox + canvas.clientWidth) / TILE));
    const lastY = Math.min(state.map.height - 1, Math.ceil((oy + canvas.clientHeight) / TILE));

    for (let y = firstY; y <= lastY; y++) {
        const row = state.map.collision[y];
        for (let x = firstX; x <= lastX; x++) {
            const sx = Math.round(x * TILE - ox);
            const sy = Math.round(y * TILE - oy);

            if (row[x] === '#') {
                ctx.fillStyle = '#39404d';
                ctx.fillRect(sx, sy, TILE, TILE);
                ctx.fillStyle = '#454d5c';
                ctx.fillRect(sx, sy, TILE, 4);
            } else {
                ctx.fillStyle = (x + y) % 2 === 0 ? '#232b2b' : '#202727';
                ctx.fillRect(sx, sy, TILE, TILE);
            }
        }
    }
}

/**
 * Outlines the tile under the cursor, in two flavours. The client reads this
 * straight off the collision grid it was handed in `init`, so it can say up
 * front whether a click is worth making - without pretending to decide.
 */
function drawHover(ox, oy) {
    if (!state.hover) return;
    const { x, y } = state.hover;
    const sx = Math.round(x * TILE - ox);
    const sy = Math.round(y * TILE - oy);

    const door = doorAt(x, y);
    ctx.lineWidth = 2;
    ctx.strokeStyle = creatureAt(x, y) ? 'rgba(240,192,122,.9)'
            : door ? 'rgba(232,200,122,.95)'
            : isBlocked(x, y) ? 'rgba(224,108,117,.75)' : 'rgba(110,168,254,.75)';
    ctx.strokeRect(sx + 1, sy + 1, TILE - 2, TILE - 2);

    if (door) {
        // Where it goes, over the tile. Whether you may go there is not said -
        // the server answers that when you step on it, and saying it twice is
        // how the two answers start disagreeing.
        ctx.font = '11px system-ui, sans-serif';
        ctx.textAlign = 'center';
        ctx.fillStyle = '#0f1116';
        ctx.fillText(door.name, sx + TILE / 2 + 1, sy - 3);
        ctx.fillStyle = '#e8c87a';
        ctx.fillText(door.name, sx + TILE / 2, sy - 4);
    }
}

function doorAt(x, y) {
    return (state.map && state.map.doors || []).find((d) => d.x === x && d.y === y) || null;
}

/** Shows where you asked to go. Not where you are going - the server decides that. */
function drawMarker(ox, oy, now) {
    if (!state.marker) return;
    const age = (now - state.marker.at) / MARKER_LIFETIME_MS;
    if (age >= 1) {
        state.marker = null;
        return;
    }

    const px = (state.marker.x + 0.5) * TILE - ox;
    const py = (state.marker.y + 0.5) * TILE - oy;

    ctx.globalAlpha = 1 - age;
    ctx.lineWidth = 2;
    ctx.strokeStyle = '#6ea8fe';
    ctx.beginPath();
    ctx.arc(px, py, 4 + age * 12, 0, Math.PI * 2);
    ctx.stroke();
    ctx.globalAlpha = 1;
}

function drawActor(actor, ox, oy, now) {
    const bob = (actor.walk || 0) * 2;
    const px = actor.rx * TILE - ox + TILE / 2;
    const py = actor.ry * TILE - oy + TILE / 2 - bob;
    const isSelf = actor.id === state.selfId;
    const mob = actor.kind === 'MOB' ? (MOB_STYLE[actor.tier] || MOB_STYLE.MOB) : null;
    const npc = actor.kind === 'NPC' ? (NPC_STYLE[actor.npcKind] || NPC_STYLE.PERSON) : null;
    const look = mob || npc;
    const radius = look ? look.radius : 10;

    // Somebody lying there is drawn flat and dim. Without it a knocked out
    // character is indistinguishable from one whose connection has gone.
    const down = !!actor.unconscious;
    ctx.globalAlpha = (actor.online ? 1 : 0.4) * (down ? 0.55 : 1);

    // The shadow stays on the ground while the body rises, which is what sells
    // the bob as a stride rather than the whole sprite sliding upward.
    ctx.beginPath();
    ctx.ellipse(px, py + radius + 1 + bob, radius - bob * 0.6, 4, 0, 0, Math.PI * 2);
    ctx.fillStyle = 'rgba(0,0,0,.35)';
    ctx.fill();

    // An elite and above wears a ring, so it is obvious before you are close
    // enough to read the name.
    if (mob && mob.ring) {
        ctx.beginPath();
        ctx.arc(px, py, radius + 4, 0, Math.PI * 2);
        ctx.lineWidth = 1.5;
        ctx.strokeStyle = mob.ring;
        ctx.globalAlpha = (actor.online ? 1 : 0.4) * 0.55;
        ctx.stroke();
        ctx.globalAlpha = actor.online ? 1 : 0.4;
    }

    ctx.beginPath();
    if (down) {
        ctx.ellipse(px, py + radius - 3, radius, radius * 0.45, 0, 0, Math.PI * 2);
    } else {
        ctx.arc(px, py, radius, 0, Math.PI * 2);
    }
    ctx.fillStyle = look ? look.fill : (isSelf ? '#6ea8fe' : `hsl(${(actor.id * 67) % 360} 45% 58%)`);
    ctx.fill();
    ctx.lineWidth = 2;
    ctx.strokeStyle = look ? look.stroke : (isSelf ? '#e8f0ff' : 'rgba(0,0,0,.45)');
    ctx.stroke();

    // Which way the actor faces, as a notch on the rim.
    const facing = { UP: [0, -1], DOWN: [0, 1], LEFT: [-1, 0], RIGHT: [1, 0] }[actor.dir] || [0, 1];
    ctx.beginPath();
    ctx.arc(px + facing[0] * (radius - 4), py + facing[1] * (radius - 4), 2.5, 0, Math.PI * 2);
    ctx.fillStyle = '#0f1116';
    ctx.fill();

    if (shouldName(actor, mob)) {
        ctx.font = '11px system-ui, sans-serif';
        ctx.textAlign = 'center';
        ctx.fillStyle = '#0f1116';
        ctx.fillText(actor.name, px + 1, py - radius - 5);
        ctx.fillStyle = look ? look.label : (isSelf ? '#cfe0ff' : '#c2c9d6');
        ctx.fillText(actor.name, px, py - radius - 6);
    }

    if (actor.maxHp > 0 && actor.hp < actor.maxHp) {
        drawHealthBar(actor, px, py - radius - (shouldName(actor, mob) ? 18 : 6));
    }

    if (actor.inFight) {
        ctx.beginPath();
        ctx.arc(px, py, radius + 7, 0, Math.PI * 2);
        ctx.lineWidth = 1;
        ctx.strokeStyle = 'rgba(224,108,117,.7)';
        ctx.stroke();
    }

    if (actor.bubble && actor.bubble.until > now) {
        drawBubble(actor.bubble.text, px, py - 32);
    } else {
        actor.bubble = null;
    }

    ctx.globalAlpha = 1;
}

/** A bar only appears once something is hurt: a map of full bars is just noise. */
function drawHealthBar(actor, px, py) {
    const width = 26;
    const ratio = Math.max(0, Math.min(1, actor.hp / actor.maxHp));
    ctx.fillStyle = 'rgba(15,17,22,.85)';
    ctx.fillRect(px - width / 2 - 1, py - 4, width + 2, 5);
    ctx.fillStyle = ratio > 0.5 ? '#77c36a' : ratio > 0.2 ? '#e0b457' : '#e06c75';
    ctx.fillRect(px - width / 2, py - 3, width * ratio, 3);
}

/** Damage rises from whoever took it and fades, so a round is readable as it happens. */
function drawFloaters(ox, oy, now) {
    state.floaters = state.floaters.filter((floater) => floater.until > now);
    ctx.font = 'bold 13px system-ui, sans-serif';
    ctx.textAlign = 'center';

    for (const floater of state.floaters) {
        // Pinned where the blow landed, not to whoever took it: a death sends
        // the body back to the spawn, and the number must not travel with it.
        const age = (now - floater.born) / FLOATER_LIFETIME_MS;
        const px = floater.x * TILE - ox + TILE / 2;
        const py = floater.y * TILE - oy + TILE / 2 - 20 - age * 18;

        ctx.globalAlpha = 1 - age;
        ctx.fillStyle = '#0f1116';
        ctx.fillText(floater.text, px + 1, py + 1);
        ctx.fillStyle = floater.colour;
        ctx.fillText(floater.text, px, py);
    }
    ctx.globalAlpha = 1;
}

/** Everything is named except a common creature standing far away. */
function shouldName(actor, mob) {
    if (actor.kind === 'NPC') {
        return true; // somebody worth walking over to is somebody worth naming
    }
    if (!mob || mob.ring) {
        return true; // players, and anything elite or above
    }
    const self = state.actors.get(state.selfId);
    if (!self) {
        return true;
    }
    const distance = Math.max(Math.abs(actor.x - self.x), Math.abs(actor.y - self.y));
    return distance <= NAME_RADIUS_TILES;
}

function drawBubble(text, px, py) {
    ctx.font = '12px system-ui, sans-serif';
    const width = ctx.measureText(text).width + 14;
    ctx.fillStyle = 'rgba(15,17,22,.92)';
    ctx.strokeStyle = '#2b303c';
    ctx.lineWidth = 1;
    ctx.beginPath();
    ctx.roundRect(px - width / 2, py - 18, width, 22, 6);
    ctx.fill();
    ctx.stroke();
    ctx.fillStyle = '#d7dbe3';
    ctx.textAlign = 'center';
    ctx.fillText(text, px, py - 3);
}

// --------------------------------------------------------------------- input

const typing = () => document.activeElement === chatInput;

function requestMove(x, y) {
    if (!state.map || x < 0 || y < 0 || x >= state.map.width || y >= state.map.height) return;
    // No local prediction and no local path: the server decides whether this is
    // even reachable. Clicking a wall is ignored by it, not by us.
    send({ type: 'move', x, y });
}

canvas.addEventListener('mousemove', (event) => {
    state.hover = tileAt(event);
});

canvas.addEventListener('mouseleave', () => {
    state.hover = null;
});

canvas.addEventListener('click', (event) => {
    const tile = tileAt(event);
    if (!tile) return;

    // A creature standing on the tile is what you meant to click. The server
    // walks you there and starts the fight; asking the player to line themselves
    // up first would be an interface chore pretending to be a rule.
    const creature = creatureAt(tile.x, tile.y);
    if (creature) {
        state.marker = { x: tile.x, y: tile.y, at: performance.now() };
        send({ type: 'attack', targetId: creature.id });
        return;
    }

    // Somebody to talk to. The server refuses from a distance, so walk over
    // first and ask on arrival - the click means "go and talk", exactly as a
    // click on a creature means "go and fight".
    const person = npcAt(tile.x, tile.y);
    if (person) {
        // Up to them, not onto them. Their tile is walkable - nothing about an
        // NPC blocks movement - so asking for it would end with the player
        // standing inside the person they came to talk to.
        const spot = besideThem(person);
        state.marker = { x: spot.x, y: spot.y, at: performance.now() };
        state.walkingUpTo = person.id;
        requestMove(spot.x, spot.y);
        return;
    }
    state.walkingUpTo = null;

    state.marker = { x: tile.x, y: tile.y, at: performance.now() };
    requestMove(tile.x, tile.y);
});

/** The free tile next to them that we are already closest to. */
function besideThem(them) {
    const self = state.actors.get(state.selfId);
    const around = [[0, 1], [0, -1], [1, 0], [-1, 0]]
        .map(([dx, dy]) => ({ x: them.x + dx, y: them.y + dy }))
        .filter((spot) => !isBlocked(spot.x, spot.y));
    if (!around.length || !self) {
        return { x: them.x, y: them.y };
    }
    around.sort((a, b) =>
        (Math.abs(a.x - self.x) + Math.abs(a.y - self.y))
        - (Math.abs(b.x - self.x) + Math.abs(b.y - self.y)));
    return around[0];
}

function npcAt(x, y) {
    for (const actor of state.actors.values()) {
        if (actor.kind === 'NPC' && actor.x === x && actor.y === y) {
            return actor;
        }
    }
    return null;
}

function creatureAt(x, y) {
    for (const actor of state.actors.values()) {
        if (actor.kind === 'MOB' && actor.hp > 0 && actor.x === x && actor.y === y) {
            return actor;
        }
    }
    return null;
}

function tileAt(event) {
    if (!state.map) return null;
    const rect = canvas.getBoundingClientRect();
    const { ox, oy } = cameraOrigin();
    const x = Math.floor((event.clientX - rect.left + ox) / TILE);
    const y = Math.floor((event.clientY - rect.top + oy) / TILE);
    if (x < 0 || y < 0 || x >= state.map.width || y >= state.map.height) return null;
    return { x, y };
}

/**
 * Walks one tile in the given direction.
 *
 * The step is measured from the character's authoritative tile, never from the
 * interpolated drawing position, so hammering a key cannot walk the render
 * ahead of what the server has agreed to.
 */
function stepInDirection(direction, now) {
    const self = state.actors.get(state.selfId);
    if (!self) return;
    state.lastKeyMoveAt = now;
    requestMove(self.x + direction[0], self.y + direction[1]);
}

/**
 * Keeps walking while a key stays down.
 *
 * Only the repeat lives here. The first step is taken on the keydown itself:
 * driving it from the render loop alone loses a quick tap entirely, because the
 * key is released again before the next frame runs.
 */
function repeatHeldMovement(now) {
    if (!state.held.size || typing()) return;
    if (now - state.lastKeyMoveAt < KEY_REPEAT_MS) return;

    const newest = [...state.held][state.held.size - 1];
    const direction = MOVEMENT_KEYS[newest];
    if (direction) stepInDirection(direction, now);
}

document.addEventListener('keydown', (event) => {
    if (event.key === 'F3') {
        event.preventDefault();
        state.debug = !state.debug;
        debugEl.hidden = !state.debug;
        return;
    }

    if (typing()) {
        if (event.key === 'Escape') chatInput.blur();
        return;
    }

    if (event.key === 'Enter') {
        event.preventDefault();
        chatInput.focus();
        return;
    }

    if (event.key === 'i' || event.key === 'I') {
        event.preventDefault();
        togglePanel();
        return;
    }

    if (event.key === 'Escape' && !panelEl.hidden) {
        togglePanel(false);
        return;
    }

    const direction = MOVEMENT_KEYS[event.key];
    if (direction) {
        event.preventDefault(); // movement keys must not also scroll the page
        if (event.repeat) return; // our own timer paces the walk, not the OS
        state.held.add(event.key);
        stepInDirection(direction, performance.now());
    }
});

panelToggle.addEventListener('click', () => togglePanel());

// The panel is a panel, not a form: nothing inside it takes the keyboard, so
// movement keys keep working while it is open. The chat box already taught us
// what happens when part of the interface quietly swallows "w".


document.addEventListener('keyup', (event) => {
    state.held.delete(event.key);
});

// A window that loses focus mid-stride would otherwise keep walking forever.
window.addEventListener('blur', () => state.held.clear());

chatInput.addEventListener('keydown', (event) => {
    if (event.key !== 'Enter') return;
    event.preventDefault();
    // This Enter belongs to the chat box. Without stopping it the document
    // listener sees the very same keystroke a moment later, finds the box
    // already blurred, and dutifully focuses it again - so the keyboard never
    // actually returns to the game.
    event.stopPropagation();
    const text = chatInput.value.trim();
    chatInput.value = '';
    if (text) send({ type: 'chat', text });
    // Focus always goes back to the game: in a game you need to be able to run
    // away mid-sentence, and a chat box that quietly keeps the keyboard makes
    // the next keypress do nothing at all.
    chatInput.blur();
});

// ------------------------------------------------------------------ screens

const screens = {
    auth: document.getElementById('auth'),
    select: document.getElementById('select'),
    game: document.getElementById('game'),
};

let rendering = false;

function show(name) {
    for (const [key, element] of Object.entries(screens)) element.hidden = key !== name;
    if (name !== 'game') {
        // Leaving the world takes the panel and its contents with it. A bag
        // left on screen belongs to a character this client no longer is.
        state.bag = null;
        state.skills = null;
        state.you = null;
        skillbarEl.hidden = true;
        togglePanel(false);
        return;
    }
    resize();
    if (!rendering) {
        rendering = true;
        requestAnimationFrame(frame);
    }
}

function showError(element, message) {
    element.textContent = message;
    element.hidden = !message;
}

/** Every call to the server goes through here, so errors are reported the same way. */
async function api(path, options = {}) {
    const response = await fetch(path, {
        ...options,
        headers: options.body ? { 'Content-Type': 'application/json' } : undefined,
    });
    if (response.status === 204) return null;

    const body = await response.json().catch(() => ({}));
    if (!response.ok) {
        const error = new Error(body.error || `Błąd serwera (${response.status}).`);
        error.status = response.status;
        throw error;
    }
    return body;
}

// --------------------------------------------------------------------- auth

const authError = document.getElementById('auth-error');
const selectError = document.getElementById('select-error');

function selectTab(name) {
    for (const tab of document.querySelectorAll('.tab')) {
        tab.setAttribute('aria-selected', String(tab.dataset.tab === name));
    }
    document.getElementById('login-form').hidden = name !== 'login';
    document.getElementById('register-form').hidden = name !== 'register';
    showError(authError, '');
}

for (const tab of document.querySelectorAll('.tab')) {
    tab.addEventListener('click', () => selectTab(tab.dataset.tab));
}

/**
 * Always lands on the login tab. Someone arriving here has either just logged
 * out or had their session expire - both want to log in, and showing them a
 * registration form because it was the last tab they opened is a small way of
 * asking the wrong question.
 */
function showAuth() {
    selectTab('login');
    renderClassChoices(document.getElementById('register-classes'), 'register-class');
    show('auth');
}

document.getElementById('login-form').addEventListener('submit', async (event) => {
    event.preventDefault();
    showError(authError, '');
    try {
        await api('/api/login', {
            method: 'POST',
            body: JSON.stringify({
                login: document.getElementById('login-name').value,
                password: document.getElementById('login-password').value,
            }),
        });
        document.getElementById('login-password').value = '';
        await returnToSelection();
    } catch (error) {
        showError(authError, error.message);
    }
});

// ------------------------------------------------------------- classes

/**
 * The classes on offer, fetched once and drawn into both places that ask for
 * one. Served by the server rather than written into the page, so that adding
 * a class is a content change and nothing else.
 */
let classesPromise = null;

function classesOnOffer() {
    if (!classesPromise) {
        classesPromise = api('/api/characters/classes')
            .then((body) => body.classes || [])
            .catch(() => {
                // The picker is a convenience: registering without one gets the
                // default class, so a failure here must not block the form.
                classesPromise = null;
                return [];
            });
    }
    return classesPromise;
}

async function renderClassChoices(fieldset, groupName) {
    const classes = await classesOnOffer();
    fieldset.hidden = classes.length === 0;
    for (const existing of [...fieldset.querySelectorAll('label')]) existing.remove();

    classes.forEach((klass, index) => {
        const label = document.createElement('label');
        const radio = document.createElement('input');
        radio.type = 'radio';
        radio.name = groupName;
        radio.value = klass.id;
        radio.checked = index === 0;

        const name = document.createElement('span');
        name.className = 'class-name';
        name.textContent = klass.name;

        const about = document.createElement('span');
        about.className = 'class-about';
        about.textContent = `${klass.description} (siła ${klass.strength}, `
            + `zwinność ${klass.agility}, inteligencja ${klass.intellect})`;

        label.append(radio, name, about);
        fieldset.append(label);
    });
}

/** What a class is called, for a screen that has only its id. */
async function nameOfClass(classId) {
    const found = (await classesOnOffer()).find((klass) => klass.id === classId);
    return found ? found.name : (classId || 'bez klasy');
}

const chosenClass = (groupName) => {
    const picked = document.querySelector(`input[name="${groupName}"]:checked`);
    return picked ? picked.value : null;
};

document.getElementById('register-form').addEventListener('submit', async (event) => {
    event.preventDefault();
    showError(authError, '');
    try {
        await api('/api/register', {
            method: 'POST',
            body: JSON.stringify({
                login: document.getElementById('register-login').value,
                password: document.getElementById('register-password').value,
                characterName: document.getElementById('register-character').value,
                classId: chosenClass('register-class'),
            }),
        });
        document.getElementById('register-password').value = '';
        await returnToSelection();
    } catch (error) {
        showError(authError, error.message);
    }
});

async function logout() {
    state.leaving = true;
    if (state.ws) state.ws.close();
    await api('/api/logout', { method: 'POST' }).catch(() => {});
    showAuth();
}

document.getElementById('logout-select').addEventListener('click', logout);

// --------------------------------------------------- character selection

const characterList = document.getElementById('character-list');

/**
 * Shows the account's characters, or the login screen when the session has
 * expired. Asking the server is also how we find out which of the two it is.
 */
async function returnToSelection() {
    state.leaving = true;
    if (state.ws) state.ws.close();
    state.version = 0;
    state.actors.clear();
    showError(selectError, '');

    renderClassChoices(document.getElementById('create-classes'), 'create-class');

    let characters;
    try {
        ({ characters } = await api('/api/characters'));
    } catch (error) {
        showAuth();
        if (error.status !== 401) showError(authError, error.message);
        return;
    }

    characterList.replaceChildren();
    for (const character of characters) {
        const item = document.createElement('li');
        const button = document.createElement('button');
        button.type = 'button';
        button.className = 'character';
        // textContent, never innerHTML: a character name is player input.
        const name = document.createElement('span');
        name.className = 'character-name';
        name.textContent = character.name;
        const where = document.createElement('span');
        where.className = 'character-where';
        const klass = await nameOfClass(character.classId);
        where.textContent = `${klass}, poziom ${character.level} · ${character.mapId}`;
        button.append(name, where);
        button.addEventListener('click', () => {
            show('game');
            connect(character.key);
        });
        item.append(button);
        characterList.append(item);
    }

    if (!characters.length) {
        const empty = document.createElement('li');
        empty.className = 'empty';
        empty.textContent = 'Nie masz jeszcze żadnej postaci.';
        characterList.append(empty);
    }

    show('select');
}

document.getElementById('create-character').addEventListener('submit', async (event) => {
    event.preventDefault();
    showError(selectError, '');
    const field = document.getElementById('new-character');
    try {
        await api('/api/characters', {
            method: 'POST',
            body: JSON.stringify({ name: field.value, classId: chosenClass('create-class') }),
        });
        field.value = '';
        await returnToSelection();
    } catch (error) {
        showError(selectError, error.message);
    }
});

fleeButton.addEventListener('click', () => send({ type: 'flee' }));

document.getElementById('leave').addEventListener('click', returnToSelection);

// A session may already be waiting from a previous visit.
returnToSelection();

// ----------------------------------------------------------------------- hud

function setStatus(text, stateName) {
    statusEl.textContent = text;
    if (stateName) statusEl.dataset.state = stateName;
    else delete statusEl.dataset.state;
}

/**
 * Counters for the F3 overlay, sampled once a second.
 *
 * Deliberately no latency figure: an honest round-trip needs a ping/pong the
 * protocol does not have, and a made-up number is worse than none.
 */
function sampleStats(now) {
    const stats = state.stats;
    stats.frames++;
    if (now - stats.sampledAt < 1000) return;

    const elapsed = (now - stats.sampledAt) / 1000;
    stats.fps = Math.round(stats.frames / elapsed);
    stats.deltaRate = Math.round(stats.deltas / elapsed);
    stats.frames = 0;
    stats.deltas = 0;
    stats.sampledAt = now;

    if (state.you && state.you.wakesAt > Date.now()) {
        renderSheet(); // the countdown has to tick down on its own
    }

    if (!state.debug) return;

    let buffered = 0;
    for (const actor of state.actors.values()) {
        if (actor.id !== state.selfId) buffered = Math.max(buffered, actor.steps.length);
    }

    let mobs = 0;
    for (const actor of state.actors.values()) {
        if (actor.kind === 'MOB') mobs++;
    }

    debugEl.textContent = [
        `klatki   ${stats.fps}/s`,
        `delty    ${stats.deltaRate}/s`,
        `wersja   ${state.version}`,
        `gracze   ${state.actors.size - mobs}`,
        `stwory   ${mobs}`,
        `bufor    ${buffered} kroków (${INTERPOLATION_DELAY_MS} ms)`,
    ].join('\n');
}

function logChat(who, text) {
    appendLine('<span class="who"></span><span class="text"></span>', (li) => {
        li.querySelector('.who').textContent = `${who}: `;
        li.querySelector('.text').textContent = text;
    });
}

function logSystem(text) {
    appendLine('<span class="sys"></span>', (li) => {
        li.querySelector('.sys').textContent = text;
    });
}

/**
 * Builds the line from a fixed template and sets every dynamic part with
 * textContent, so a player cannot put markup into someone else's chat log.
 */
function appendLine(template, fill) {
    const li = document.createElement('li');
    li.innerHTML = template;
    fill(li);
    chatLog.appendChild(li);
    while (chatLog.childElementCount > 100) chatLog.removeChild(chatLog.firstElementChild);
    chatLog.scrollTop = chatLog.scrollHeight;
}
