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
    state.version = msg.v;
    state.selfId = msg.selfId;
    state.map = msg.map;

    state.actors.clear();
    for (const dto of msg.actors || []) upsertActor(dto);

    mapNameEl.textContent = msg.map.name;
    setStatus('połączono');
    if (state.everConnected) logSystem('Wczytano świat od nowa.');
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
        if (change.id === state.selfId) updateFleeButton();
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
    updateFleeButton();
}

function applyBag(msg) {
    state.bag = msg;
    renderPanel();
}

function renderSheet() {
    if (!state.you) return;
    const you = state.you;
    const healthPercent = Math.max(0, Math.round((you.hp / you.maxHp) * 100));
    const xpPercent = you.xpForNextLevel > 0
        ? Math.max(0, Math.min(100, Math.round((you.xpThisLevel / you.xpForNextLevel) * 100)))
        : 0;
    const weakened = you.weakenedUntil > Date.now();

    sheetEl.innerHTML = '';
    sheetEl.append(
        bar('hp', `${you.hp} / ${you.maxHp}`, healthPercent),
        // Mana has no spender yet - intellect is the attribute classes will
        // eventually pay from. Shown rather than hidden so an item granting
        // intellect visibly does something today.
        bar('mana', `${you.mana} / ${you.maxMana}`, you.maxMana > 0 ? 100 : 0),
        bar('xp', `poziom ${you.level}`, xpPercent),
    );
    if (weakened) {
        const note = document.createElement('div');
        note.className = 'weakened';
        note.textContent = `osłabienie: ${Math.ceil((you.weakenedUntil - Date.now()) / 1000)} s`;
        sheetEl.append(note);
    }
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
    for (const actor of state.actors.values()) advanceAnimation(actor, now);

    const { ox, oy } = cameraOrigin();
    ctx.fillStyle = '#0f1116';
    ctx.fillRect(0, 0, canvas.clientWidth, canvas.clientHeight);

    drawTiles(ox, oy);
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

    ctx.lineWidth = 2;
    ctx.strokeStyle = creatureAt(x, y) ? 'rgba(240,192,122,.9)'
            : isBlocked(x, y) ? 'rgba(224,108,117,.75)' : 'rgba(110,168,254,.75)';
    ctx.strokeRect(sx + 1, sy + 1, TILE - 2, TILE - 2);
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
    const radius = mob ? mob.radius : 10;

    ctx.globalAlpha = actor.online ? 1 : 0.4;

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
    ctx.arc(px, py, radius, 0, Math.PI * 2);
    ctx.fillStyle = mob ? mob.fill : (isSelf ? '#6ea8fe' : `hsl(${(actor.id * 67) % 360} 45% 58%)`);
    ctx.fill();
    ctx.lineWidth = 2;
    ctx.strokeStyle = mob ? mob.stroke : (isSelf ? '#e8f0ff' : 'rgba(0,0,0,.45)');
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
        ctx.fillStyle = mob ? mob.label : (isSelf ? '#cfe0ff' : '#c2c9d6');
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

    state.marker = { x: tile.x, y: tile.y, at: performance.now() };
    requestMove(tile.x, tile.y);
});

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
        state.you = null;
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

    if (state.you && state.you.weakenedUntil > Date.now()) {
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
