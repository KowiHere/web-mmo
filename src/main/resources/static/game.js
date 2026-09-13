'use strict';

/**
 * The client is a renderer and an input device. It owns no game truth: it draws
 * what the last delta said and asks the server for everything else. The only
 * thing it invents is the motion *between* two server states, so that a 10 Hz
 * tick looks continuous at 60 fps.
 */

const TILE = 32;

const state = {
    ws: null,
    version: 0,
    selfId: null,
    map: null,
    actors: new Map(),
    reconnectDelay: 1000,
    everConnected: false,
};

const canvas = document.getElementById('view');
const ctx = canvas.getContext('2d');
const statusEl = document.getElementById('status');
const mapNameEl = document.getElementById('map-name');
const chatLog = document.getElementById('chat-log');
const chatInput = document.getElementById('chat-input');

// ---------------------------------------------------------------- networking

function connect(name) {
    const proto = location.protocol === 'https:' ? 'wss:' : 'ws:';
    const ws = new WebSocket(`${proto}//${location.host}/ws`);
    state.ws = ws;
    setStatus('laczenie...', 'down');

    ws.onopen = () => {
        state.reconnectDelay = 1000;
        // The token is what makes a reconnect a *resume*: the server still has
        // our actor standing where we left it. `since` asks for the gap only.
        ws.send(JSON.stringify({
            type: 'hello',
            name,
            token: sessionStorage.getItem('mmo.token'),
            since: state.version,
        }));
    };

    ws.onmessage = (event) => {
        const msg = JSON.parse(event.data);
        if (msg.type === 'init') applyInit(msg);
        else if (msg.type === 'delta') applyDelta(msg);
        else if (msg.type === 'error') logSystem(msg.message);
    };

    ws.onclose = () => {
        setStatus('rozlaczono, ponawiam...', 'down');
        setTimeout(() => connect(name), state.reconnectDelay);
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
    sessionStorage.setItem('mmo.token', msg.token);

    state.actors.clear();
    for (const dto of msg.actors || []) upsertActor(dto);

    mapNameEl.textContent = msg.map.name;
    setStatus('polaczono');
    if (state.everConnected) logSystem('Wczytano swiat od nowa.');
    state.everConnected = true;
}

function applyDelta(msg) {
    // A gap means we missed frames and our picture is no longer trustworthy.
    // Reloading is the honest response; silently drifting is not.
    if (msg.v !== state.version + 1 && state.version !== 0) {
        location.reload();
        return;
    }
    state.version = msg.v;
    setStatus('polaczono');

    for (const dto of msg.joined || []) upsertActor(dto);
    for (const id of msg.left || []) state.actors.delete(id);

    for (const mv of msg.moved || []) {
        const actor = state.actors.get(mv.id);
        if (!actor) continue;
        actor.x = mv.x;
        actor.y = mv.y;
        actor.dir = mv.dir;
        actor.anim = { fx: mv.fx, fy: mv.fy, tx: mv.x, ty: mv.y, t0: performance.now(), ms: mv.ms };
    }

    for (const p of msg.presence || []) {
        const actor = state.actors.get(p.id);
        if (!actor) continue;
        actor.online = p.online;
        logSystem(`${actor.name} ${p.online ? 'wrocil do gry' : 'stracil polaczenie'}.`);
    }

    for (const line of msg.chat || []) {
        const actor = state.actors.get(line.id);
        if (actor) actor.bubble = { text: line.text, until: performance.now() + 4500 };
        logChat(line.name, line.text);
    }
}

function upsertActor(dto) {
    const existing = state.actors.get(dto.id);
    if (existing) {
        Object.assign(existing, dto);
        return;
    }
    state.actors.set(dto.id, { ...dto, rx: dto.x, ry: dto.y, anim: null, bubble: null });
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

function frame(now) {
    requestAnimationFrame(frame);
    if (!state.map) return;

    for (const actor of state.actors.values()) advanceAnimation(actor, now);

    const { ox, oy } = cameraOrigin();
    ctx.fillStyle = '#0f1116';
    ctx.fillRect(0, 0, canvas.clientWidth, canvas.clientHeight);

    drawTiles(ox, oy);

    // Painter's order: whoever is further down the map is drawn last, so an
    // actor in front correctly overlaps one behind.
    const actors = [...state.actors.values()].sort((a, b) => a.ry - b.ry);
    for (const actor of actors) drawActor(actor, ox, oy, now);
}

function advanceAnimation(actor, now) {
    const anim = actor.anim;
    if (!anim) {
        actor.rx = actor.x;
        actor.ry = actor.y;
        return;
    }
    const t = clamp((now - anim.t0) / anim.ms, 0, 1);
    actor.rx = anim.fx + (anim.tx - anim.fx) * t;
    actor.ry = anim.fy + (anim.ty - anim.fy) * t;
    if (t >= 1) actor.anim = null;
}

function drawTiles(ox, oy) {
    const firstX = Math.max(0, Math.floor(ox / TILE));
    const firstY = Math.max(0, Math.floor(oy / TILE));
    const lastX = Math.min(state.map.width - 1, Math.ceil((ox + canvas.clientWidth) / TILE));
    const lastY = Math.min(state.map.height - 1, Math.ceil((oy + canvas.clientHeight) / TILE));

    for (let y = firstY; y <= lastY; y++) {
        const row = state.map.collision[y];
        for (let x = firstX; x <= lastX; x++) {
            const blocked = row[x] === '#';
            const sx = Math.round(x * TILE - ox);
            const sy = Math.round(y * TILE - oy);

            if (blocked) {
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

function drawActor(actor, ox, oy, now) {
    const px = actor.rx * TILE - ox + TILE / 2;
    const py = actor.ry * TILE - oy + TILE / 2;
    const isSelf = actor.id === state.selfId;

    ctx.globalAlpha = actor.online ? 1 : 0.4;

    ctx.beginPath();
    ctx.ellipse(px, py + 11, 10, 4, 0, 0, Math.PI * 2);
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

canvas.addEventListener('click', (event) => {
    if (!state.map) return;
    const rect = canvas.getBoundingClientRect();
    const { ox, oy } = cameraOrigin();
    const x = Math.floor((event.clientX - rect.left + ox) / TILE);
    const y = Math.floor((event.clientY - rect.top + oy) / TILE);
    if (x < 0 || y < 0 || x >= state.map.width || y >= state.map.height) return;
    // No local prediction and no local path: the server decides whether this is
    // even reachable. Clicking a wall is simply ignored, by it and not by us.
    send({ type: 'move', x, y });
});

chatInput.addEventListener('keydown', (event) => {
    if (event.key !== 'Enter') return;
    const text = chatInput.value.trim();
    chatInput.value = '';
    if (text) send({ type: 'chat', text });
});

document.getElementById('join-form').addEventListener('submit', (event) => {
    event.preventDefault();
    const name = document.getElementById('name').value.trim() || 'Wanderer';
    document.getElementById('join').hidden = true;
    document.getElementById('game').hidden = false;
    resize();
    connect(name);
    requestAnimationFrame(frame);
});

// ----------------------------------------------------------------------- hud

function setStatus(text, stateName) {
    statusEl.textContent = text;
    if (stateName) statusEl.dataset.state = stateName;
    else delete statusEl.dataset.state;
}

function logChat(who, text) {
    appendLine(`<span class="who"></span><span class="text"></span>`, (li) => {
        li.querySelector('.who').textContent = `${who}: `;
        li.querySelector('.text').textContent = text;
    });
}

function logSystem(text) {
    appendLine(`<span class="sys"></span>`, (li) => {
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
