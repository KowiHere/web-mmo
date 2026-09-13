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

const MARKER_LIFETIME_MS = 600;
const BUBBLE_LIFETIME_MS = 4500;

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
    stats: { frames: 0, fps: 0, deltas: 0, deltaRate: 0, sampledAt: 0 },
};

const canvas = document.getElementById('view');
const ctx = canvas.getContext('2d');
const statusEl = document.getElementById('status');
const mapNameEl = document.getElementById('map-name');
const chatLog = document.getElementById('chat-log');
const chatInput = document.getElementById('chat-input');
const debugEl = document.getElementById('debug');

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

    for (const dto of msg.joined || []) upsertActor(dto);
    for (const id of msg.left || []) state.actors.delete(id);

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
        logSystem(`${actor.name} ${p.online ? 'wrócił do gry' : 'stracił połączenie'}.`);
    }

    for (const line of msg.chat || []) {
        const actor = state.actors.get(line.id);
        if (actor) actor.bubble = { text: line.text, until: now + BUBBLE_LIFETIME_MS };
        logChat(line.name, line.text);
    }
}

function upsertActor(dto) {
    const existing = state.actors.get(dto.id);
    if (existing) {
        Object.assign(existing, dto);
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
    // actor in front correctly overlaps one behind.
    const actors = [...state.actors.values()].sort((a, b) => a.ry - b.ry);
    for (const actor of actors) drawActor(actor, ox, oy, now);

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
    ctx.strokeStyle = isBlocked(x, y) ? 'rgba(224,108,117,.75)' : 'rgba(110,168,254,.75)';
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

    ctx.globalAlpha = actor.online ? 1 : 0.4;

    // The shadow stays on the ground while the body rises, which is what sells
    // the bob as a stride rather than the whole sprite sliding upward.
    ctx.beginPath();
    ctx.ellipse(px, py + 11 + bob, 10 - bob * 0.6, 4, 0, 0, Math.PI * 2);
    ctx.fillStyle = 'rgba(0,0,0,.35)';
    ctx.fill();

    ctx.beginPath();
    ctx.arc(px, py, 10, 0, Math.PI * 2);
    ctx.fillStyle = isSelf ? '#6ea8fe' : `hsl(${(actor.id * 67) % 360} 45% 58%)`;
    ctx.fill();
    ctx.lineWidth = 2;
    ctx.strokeStyle = isSelf ? '#e8f0ff' : 'rgba(0,0,0,.45)';
    ctx.stroke();

    // Which way the actor faces, as a notch on the rim.
    const facing = { UP: [0, -1], DOWN: [0, 1], LEFT: [-1, 0], RIGHT: [1, 0] }[actor.dir] || [0, 1];
    ctx.beginPath();
    ctx.arc(px + facing[0] * 6, py + facing[1] * 6, 2.5, 0, Math.PI * 2);
    ctx.fillStyle = '#0f1116';
    ctx.fill();

    ctx.font = '11px system-ui, sans-serif';
    ctx.textAlign = 'center';
    ctx.fillStyle = '#0f1116';
    ctx.fillText(actor.name, px + 1, py - 15);
    ctx.fillStyle = isSelf ? '#cfe0ff' : '#c2c9d6';
    ctx.fillText(actor.name, px, py - 16);

    if (actor.bubble && actor.bubble.until > now) {
        drawBubble(actor.bubble.text, px, py - 32);
    } else {
        actor.bubble = null;
    }

    ctx.globalAlpha = 1;
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
    state.marker = { x: tile.x, y: tile.y, at: performance.now() };
    requestMove(tile.x, tile.y);
});

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

    const direction = MOVEMENT_KEYS[event.key];
    if (direction) {
        event.preventDefault(); // movement keys must not also scroll the page
        if (event.repeat) return; // our own timer paces the walk, not the OS
        state.held.add(event.key);
        stepInDirection(direction, performance.now());
    }
});

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
    if (name !== 'game') return;
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
        where.textContent = `${character.mapId} · ${character.x},${character.y}`;
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
            body: JSON.stringify({ name: field.value }),
        });
        field.value = '';
        await returnToSelection();
    } catch (error) {
        showError(selectError, error.message);
    }
});

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

    if (!state.debug) return;

    let buffered = 0;
    for (const actor of state.actors.values()) {
        if (actor.id !== state.selfId) buffered = Math.max(buffered, actor.steps.length);
    }

    debugEl.textContent = [
        `klatki   ${stats.fps}/s`,
        `delty    ${stats.deltaRate}/s`,
        `wersja   ${state.version}`,
        `aktorzy  ${state.actors.size}`,
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
