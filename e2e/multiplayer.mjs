/**
 * Drives two real browser tabs against a running server and checks the things
 * unit tests cannot: that two people register, pick a character and see each
 * other move, that the server refuses an illegal destination, and - the one
 * that matters most now - that a logged-in account cannot enter the world as
 * somebody else's character.
 *
 * Usage:
 *   ./mvnw spring-boot:run          # in another terminal
 *   cd e2e && npm install && npm test
 *
 * Set GAME_URL to point at a server somewhere other than localhost:8080, and
 * CHROMIUM_PATH to use a browser Playwright did not download itself.
 */
import { chromium } from 'playwright';

const URL = process.env.GAME_URL || 'http://localhost:8080/';
const STEP_MS = 300; // one tile, per MapRunner.STEP_TICKS * TICK_MS
const PASSWORD = 'haslo-do-testow';

// Logins and character names are unique for ever, and this database survives
// the run - so each run needs its own.
const TAG = Math.random().toString(36).slice(2, 6);
const ALA = `Ala-${TAG}`;
const BOB = `Bob-${TAG}`;

let failures = 0;
const ok = (message) => console.log(`ok   - ${message}`);
const fail = (message) => {
    console.error(`FAIL - ${message}`);
    failures++;
};

const launchOptions = { args: ['--no-sandbox'] };
if (process.env.CHROMIUM_PATH) launchOptions.executablePath = process.env.CHROMIUM_PATH;
const browser = await chromium.launch(launchOptions);

/** Registers an account, then enters the world as its first character. */
async function register(characterName, playAs) {
    // A fresh context per player: sessions live in cookies now, and two players
    // sharing one cookie jar would be one player with two tabs.
    const context = await browser.newContext({ viewport: { width: 900, height: 620 } });
    const page = await context.newPage();
    page.on('pageerror', (error) => fail(`${characterName}: uncaught ${error.message}`));

    await page.goto(URL);
    await page.click('.tab[data-tab="register"]');
    await page.fill('#register-login', characterName.toLowerCase());
    await page.fill('#register-password', PASSWORD);
    await page.fill('#register-character', characterName);
    if (playAs) {
        // Picked rather than left to the default, so that what comes back can
        // be checked against what was asked for.
        await page.check(`#register-classes input[value="${playAs}"]`);
    }
    await page.click('#register-form button[type="submit"]');

    await page.waitForSelector(`.character:has-text("${characterName}")`, { timeout: 10_000 });
    await page.click(`.character:has-text("${characterName}")`);
    await page.waitForFunction(() => state.selfId !== null, null, { timeout: 10_000 });

    // Keep every frame as it arrives. The client's own state cannot answer "was
    // this ever sent to me", which is exactly the question a leak asks.
    await page.evaluate(() => {
        // Being knocked out lasts a while, so "are you out" cannot tell a death
        // that just happened from one two phases ago. Watch for the moment it
        // changes instead, and note where the character stood right then.
        window.deaths = 0;
        let lastWakesAt = 0;
        setInterval(() => {
            if (!state.you || state.you.wakesAt <= lastWakesAt) return;
            lastWakesAt = state.you.wakesAt;
            const self = state.actors.get(state.selfId);
            if (self) window.atDeath = { at: [self.x, self.y] };
            window.deaths++;
        }, 50);

        window.raw = [];
        const socket = state.ws;
        const deliver = socket.onmessage;
        socket.onmessage = (event) => {
            window.raw.push(event.data);
            deliver(event);
        };
    });
    return page;
}

/**
 * Picks a direction the character can actually walk right now.
 *
 * Hard-coding one makes the test depend on wherever earlier steps happened to
 * leave the character - and a refusal to walk into a wall is correct behaviour,
 * so such a test fails while reporting nothing real.
 */
const pickFreeDirection = (page) => page.evaluate(() => {
    const self = state.actors.get(state.selfId);
    const options = [
        { key: 'w', dx: 0, dy: -1 },
        { key: 's', dx: 0, dy: 1 },
        { key: 'a', dx: -1, dy: 0 },
        { key: 'd', dx: 1, dy: 0 },
    ];
    const free = options.find((o) => {
        const x = self.x + o.dx;
        const y = self.y + o.dy;
        return y >= 0 && y < state.map.height && x >= 0 && x < state.map.width
            && state.map.collision[y][x] === '.';
    });
    return free && { ...free, fromX: self.x, fromY: self.y };
});

/**
 * Gets the character out of any fight it was dragged into.
 *
 * The world is live and the creatures hunt, so a character standing still
 * while the script checks something else can be attacked at any moment - and a
 * character in a fight refuses to walk, which is correct behaviour reported as
 * a failure. Fleeing is a roll, so this asks more than once.
 */
/**
 * Walks to the herbalist and is patched up, if there is anything to patch.
 *
 * <p>This exists because the milestone that added her also stopped death from
 * healing, and a character on one point of health loses every fight it starts.
 * Half this script stopped working the moment that landed - which is the loop
 * the game now has, not a quirk of the script: fight, lose, walk over, fight
 * again.
 */
/**
 * Waits for a knocked out character to come round.
 *
 * Every phase below assumes a character that can act, and since dying stopped
 * being a free trip home that assumption holds only between deaths. This is the
 * same thing a player does: sit there until the count runs out.
 */
async function waitIfOut(page) {
    const out = await page.evaluate(() => !!(state.you && state.you.wakesAt > Date.now()));
    if (!out) return true;
    return page
        .waitForFunction(() => !state.you.wakesAt || state.you.wakesAt <= Date.now(),
            null, { timeout: 180_000 })
        .then(() => true)
        .catch(() => false);
}

async function mendIfHurt(page, below = 1) {
    await waitIfOut(page);
    const now = await page.evaluate(() => (state.you ? { hp: state.you.hp, maxHp: state.you.maxHp } : null));
    if (!now || now.hp >= now.maxHp * below) return true;

    await escapeAnyFight(page);
    const healer = await page.evaluate(() =>
        [...state.actors.values()].find((a) => a.kind === 'NPC') || null);
    if (!healer) return false;

    await page.evaluate((them) => {
        const spot = besideThem(them);
        state.walkingUpTo = them.id;
        requestMove(spot.x, spot.y);
    }, healer);
    const opened = await page
        .waitForSelector('#dialogue:not([hidden])', { timeout: 30_000 })
        .then(() => true)
        .catch(() => false);
    if (!opened) return false;

    await page.click('#dialogue-options button:text-is("Opatrz mnie, proszę.")');
    const mended = await page
        .waitForFunction(() => state.you.hp === state.you.maxHp, null, { timeout: 8_000 })
        .then(() => true)
        .catch(() => false);
    await page.evaluate(() => state.ws.send(JSON.stringify({ type: 'endTalk' })));
    return mended;
}

async function escapeAnyFight(page) {
    await waitIfOut(page);
    for (let attempt = 0; attempt < 12; attempt++) {
        const fighting = await page.evaluate(() => {
            const self = state.actors.get(state.selfId);
            return !!(self && self.inFight);
        });
        if (!fighting) return true;
        await page.evaluate(() => state.ws.send(JSON.stringify({ type: 'flee' })));
        await page.waitForTimeout(1_700); // one round
    }
    return false;
}

/**
 * Taps a direction and reports whether the character actually went that way.
 *
 * Retried, because a creature can pick a fight in the moment between checking
 * and pressing - and a character in a fight refuses to walk, which is the
 * feature working rather than the key failing.
 */
/**
 * Kills whatever is nearest until the purse reaches a number.
 *
 * <p>What a run earns varies wildly - a boar pays two coins and a wolf
 * fourteen - and two sections now spend money. Without this, a poor run fails
 * checks about shops and bottles that have nothing to do with how the fighting
 * went.
 */
async function earnAtLeast(page, gold, seconds = 180) {
    const until = Date.now() + seconds * 1_000;
    while (Date.now() < until) {
        const purse = await page.evaluate(() =>
            (state.purse.coins.find((c) => c.primary) || {}).amount || 0);
        if (purse >= gold) return true;
        await waitIfOut(page);
        await mendIfHurt(page, 0.4);
        const busy = await page.evaluate(() =>
            !!(state.actors.get(state.selfId) || {}).inFight);
        if (!busy) {
            const prey = await page.evaluate(() => {
                const self = state.actors.get(state.selfId);
                const mobs = [...state.actors.values()].filter((a) => a.kind === 'MOB');
                mobs.sort((a, b) => Math.abs(a.x - self.x) + Math.abs(a.y - self.y)
                    - Math.abs(b.x - self.x) - Math.abs(b.y - self.y));
                return mobs[0] ? mobs[0].id : null;
            });
            if (prey === null) return false;
            await page.evaluate((id) =>
                state.ws.send(JSON.stringify({ type: 'attack', targetId: id })), prey);
        }
        await page.waitForTimeout(1_500);
    }
    return false;
}

async function tryStep(page, stepMs) {
    await waitIfOut(page);
    for (let attempt = 0; attempt < 3; attempt++) {
        await escapeAnyFight(page);
        const step = await pickFreeDirection(page);
        if (!step) return null;
        await page.keyboard.press(step.key);
        await page.waitForTimeout(stepMs * 3);
        const self = await page.evaluate(() => {
            const s = state.actors.get(state.selfId);
            return { x: s.x, y: s.y };
        });
        if (self.x === step.fromX + step.dx && self.y === step.fromY + step.dy) {
            return { ...step, to: self };
        }
    }
    return false;
}

/**
 * Waits until the character has actually stopped walking.
 *
 * A click gives the server a whole path, which it walks out one tile at a
 * time. Measuring anything about movement while one is still being walked
 * reads the tail of the last instruction as the result of the next one.
 */
async function waitUntilStill(page, stepMs) {
    let last = null;
    for (let attempt = 0; attempt < 40; attempt++) {
        const at = await page.evaluate(() => {
            const self = state.actors.get(state.selfId);
            return `${self.x},${self.y}`;
        });
        if (at === last) return at;
        last = at;
        await page.waitForTimeout(stepMs);
    }
    return last;
}

const snapshot = (page) => page.evaluate(() => ({
    version: state.version,
    selfId: state.selfId,
    mapId: state.map && state.map.id,
    actors: [...state.actors.values()].map((a) => ({ id: a.id, name: a.name, x: a.x, y: a.y })),
}));

try {
    // ---- two accounts share one map --------------------------------------
    const ala = await register(ALA, 'mag');
    const bob = await register(BOB, 'lowca');
    await ala.waitForFunction(() => state.actors.size >= 2, null, { timeout: 10_000 });

    const alaView = await snapshot(ala);
    const bobView = await snapshot(bob);
    const sees = (view, name) => view.actors.some((a) => a.name === name);

    alaView.mapId ? ok('init carried the map definition') : fail('init had no map');
    // Named rather than counted: a character whose owner just left lingers for
    // a grace period by design, so a headcount reports unrelated news.
    sees(alaView, ALA) && sees(alaView, BOB)
        ? ok('Ala sees both characters')
        : fail(`Ala sees ${JSON.stringify(alaView.actors.map((a) => a.name))}`);
    sees(bobView, ALA) && sees(bobView, BOB)
        ? ok('Bob sees both characters')
        : fail(`Bob sees ${JSON.stringify(bobView.actors.map((a) => a.name))}`);
    alaView.selfId !== bobView.selfId ? ok('each client owns a distinct actor') : fail('shared actor id');

    // ---- the class asked for is the class played -------------------------
    const played = await ala.evaluate(() => state.you && { id: state.you.classId, name: state.you.className });
    played && played.id === 'mag'
        ? ok(`the class chosen at registration is the one being played: ${played.name}`)
        : fail(`asked for a mage, got ${JSON.stringify(played)}`);

    // Two classes, the same level, different bodies: proof the choice reaches
    // the numbers rather than stopping at a label.
    const bobPlays = await bob.evaluate(() => ({ id: state.you.classId, maxHp: state.you.maxHp, attack: state.you.attack }));
    const alaPlays = await ala.evaluate(() => ({ id: state.you.classId, maxHp: state.you.maxHp, attack: state.you.attack }));
    alaPlays.maxHp !== bobPlays.maxHp
        ? ok(`a ${alaPlays.id} and a ${bobPlays.id} are not the same character `
            + `(${alaPlays.maxHp} vs ${bobPlays.maxHp} health)`)
        : fail(`two different classes with identical health: ${JSON.stringify(alaPlays)}`);

    // ---- ONE ACCOUNT MAY NOT PLAY ANOTHER'S CHARACTER --------------------
    // Being logged in is not the same as being entitled to this character.
    // Missing that distinction is the whole reason accounts exist.
    const stolen = await ala.evaluate((victim) => new Promise((resolve) => {
        const socket = new WebSocket(
            `ws://${location.host}/ws?character=${encodeURIComponent(victim)}`);
        socket.onopen = () => { socket.close(); resolve('opened'); };
        socket.onerror = () => resolve('refused');
        socket.onclose = (event) => resolve(event.wasClean && event.code === 1000 ? 'opened' : 'refused');
    }), BOB.toLowerCase());

    stolen === 'refused'
        ? ok("the server refused Ala a socket for Bob's character")
        : fail(`Ala opened a socket as Bob's character (${stolen})`);

    // ---- the map is populated and alive ----------------------------------
    const creatures = await ala.evaluate(() =>
        [...state.actors.values()].filter((a) => a.kind === 'MOB')
            .map((a) => ({ id: a.id, tier: a.tier, x: a.x, y: a.y })));

    creatures.length > 0
        ? ok(`the map came with ${creatures.length} creature(s)`)
        : fail('no creatures on the map');
    creatures.every((c) => c.tier)
        ? ok('every creature says which tier it is')
        : fail('a creature arrived without a tier');

    // Nobody sends a command here: anything that moves, moved by itself.
    const before = Object.fromEntries(creatures.map((c) => [c.id, `${c.x},${c.y}`]));
    await ala.waitForTimeout(3_000);
    const after = await ala.evaluate(() =>
        Object.fromEntries([...state.actors.values()].filter((a) => a.kind === 'MOB')
            .map((a) => [a.id, `${a.x},${a.y}`])));

    Object.keys(before).some((id) => after[id] && after[id] !== before[id])
        ? ok('creatures move on their own, with no player input')
        : fail('the creatures never moved by themselves');

    // ---- movement is server-driven and reaches the other client ----------
    const start = bobView.actors.find((a) => a.id === alaView.selfId);
    // On the canvas itself, and well clear of the panels along the left edge:
    // a click that lands on the party panel is a click the map never hears,
    // which says nothing about whether movement propagates.
    await ala.click('#view', { position: { x: 520, y: 300 } });
    await bob.waitForTimeout(STEP_MS * 8);

    const moved = (await snapshot(bob)).actors.find((a) => a.id === alaView.selfId);
    moved.x !== start.x || moved.y !== start.y
        ? ok(`Bob saw Ala walk ${start.x},${start.y} -> ${moved.x},${moved.y}`)
        : fail('movement did not propagate to the other client');

    // ---- the server, not the client, refuses a wall ----------------------
    await waitUntilStill(ala, STEP_MS);
    const wall = await ala.evaluate(async (settleMs) => {
        const before = { ...state.actors.get(state.selfId) };
        state.ws.send(JSON.stringify({ type: 'move', x: 0, y: 0 })); // map border
        await new Promise((resolve) => setTimeout(resolve, settleMs));
        const after = state.actors.get(state.selfId);
        return { before: [before.x, before.y], after: [after.x, after.y] };
    }, STEP_MS * 4);

    wall.after[0] === wall.before[0] && wall.after[1] === wall.before[1]
        ? ok('server refused a path into a wall')
        : fail(`actor entered a blocked tile: ${wall.before} -> ${wall.after}`);

    // ---- chat relays through the server ----------------------------------
    await ala.fill('#chat-input', 'siema z drugiej karty');
    await ala.press('#chat-input', 'Enter');
    await bob
        .waitForFunction(() => document.getElementById('chat-log').textContent.includes('siema'), null, {
            timeout: 5_000,
        })
        .then(() => ok('chat reached the other client'))
        .catch(() => fail('chat did not reach the other client'));

    // ---- a tapped key walks the character --------------------------------
    // Out of any fight first: a fight holds you where you stand, so a wolf
    // wandering past turns "the key did nothing" into a failure about the
    // keyboard that is really about the wolf.
    await escapeAnyFight(ala);
    await waitIfOut(ala);
    const step = await tryStep(ala, STEP_MS);
    if (step === null) {
        fail('no free tile next to the character; cannot test keyboard movement');
    } else if (step) {
        ok(`tapping "${step.key}" walked ${step.fromX},${step.fromY} -> ${step.to.x},${step.to.y}`);
    } else {
        fail('tapping a direction never moved the character');
    }

    // ---- typing must not walk the character ------------------------------
    // The easiest thing in the whole feature to get wrong: chatting "wasd"
    // should say a word, not send the character in four directions.
    await ala.focus('#chat-input');
    const beforeTyping = (await snapshot(ala)).actors.find((a) => a.id === alaView.selfId);
    await ala.keyboard.type('wasd');
    await ala.waitForTimeout(STEP_MS * 3);
    const afterTyping = (await snapshot(ala)).actors.find((a) => a.id === alaView.selfId);

    afterTyping.x === beforeTyping.x && afterTyping.y === beforeTyping.y
        ? ok('movement keys are inert while the chat box has focus')
        : fail(`typing moved the character ${[beforeTyping.x, beforeTyping.y]} -> ${[afterTyping.x, afterTyping.y]}`);

    // ---- Escape hands the keyboard back ----------------------------------
    await ala.keyboard.press('Escape');
    const focusedAfterEscape = await ala.evaluate(() => document.activeElement.id);
    focusedAfterEscape !== 'chat-input'
        ? ok('Escape returns control to the game')
        : fail('Escape left focus in the chat box');

    const again = await tryStep(ala, STEP_MS);
    if (again === null) {
        fail('no free tile next to the character; cannot re-test movement');
    } else if (again) {
        ok('movement works again once the chat box is left');
    } else {
        fail('the character would not move after Escape');
    }

    // ---- attacking a creature ------------------------------------------
    // Deliberately not "this boar, this kill": the map hunts back, and which
    // creature Ala ends up trading blows with is not the point. What must be
    // true is that a fight happened, that it killed something, and that it
    // paid - all driven by the server from one attack command.
    const shots = process.env.E2E_SHOTS;
    const nearestCreature = (page) => page.evaluate(() => {
        const self = state.actors.get(state.selfId);
        const creatures = [...state.actors.values()].filter((a) => a.kind === 'MOB');
        creatures.sort((a, b) =>
            Math.abs(a.x - self.x) + Math.abs(a.y - self.y)
            - Math.abs(b.x - self.x) - Math.abs(b.y - self.y));
        return creatures[0] || null;
    });

    const xpBefore = await ala.evaluate(() => (state.you ? state.you.xp : 0));
    const creaturesAtStart = await ala.evaluate(() =>
        [...state.actors.values()].filter((a) => a.kind === 'MOB').map((a) => a.id));

    // Keep at it the way a player would. A character can lose a fight before it
    // finishes one - the map is not safe, and that is the design - so a single
    // attempt proves nothing either way.
    let wounded = false;
    let killed = false;
    let looted = false;
    // Kept up until something is actually in the bag, not merely until
    // something died: only the boar drops every time, and a wolf that dies
    // without leaving anything is the loot table working rather than failing.
    // Longer than it was: a trip to the herbalist is now part of hunting.
    const huntUntil = Date.now() + 240_000;
    while (Date.now() < huntUntil && !(killed && looted)) {
        // No fleeing here: a fight already under way is as likely to end in a
        // kill as a fresh one, and running from it wastes a round every time.
        const busy = await ala.evaluate(() => {
            const self = state.actors.get(state.selfId);
            return !!(self && self.inFight);
        });
        if (!busy) {
            // Nobody picks a fight on their last point of health, and since
            // this milestone nobody survives one either. Going to be mended is
            // part of hunting now, exactly as it is for a player - and so is
            // waiting to come round after losing one.
            await waitIfOut(ala);
            await mendIfHurt(ala, 0.4);
            const prey = await nearestCreature(ala);
            if (!prey) break;
            await ala.evaluate((id) => state.ws.send(JSON.stringify({ type: 'attack', targetId: id })), prey.id);
        }

        await ala.waitForTimeout(1_500);
        const seen = await ala.evaluate((ids) => ({
            hurt: [...state.actors.values()].some((a) => a.kind === 'MOB' && a.hp < a.maxHp),
            gone: ids.some((id) => !state.actors.has(id)),
            carrying: state.bag ? (state.bag.carried || []).length : 0,
        }), creaturesAtStart);
        if (seen.hurt && !wounded && shots) await ala.screenshot({ path: `${shots}/walka.png` });
        wounded = wounded || seen.hurt;
        killed = killed || seen.gone;
        looted = looted || seen.carrying > 0;
    }

    wounded
        ? ok('one attack command walked Ala over and started the rounds')
        : fail('no creature ever took a blow');
    killed
        ? ok('a creature died and left the world')
        : fail('nothing died');

    await ala
        .waitForFunction((was) => state.you && state.you.xp > was, xpBefore, { timeout: 15_000 })
        .then(() => ok('killing it paid experience'))
        .catch(() => fail('experience did not rise after the kill'));

    // Bob has experience of his own and a "you" frame that carries it. The leak
    // would be anybody's progress arriving inside a delta, which the whole map
    // receives - so this reads the wire rather than Bob's character.
    const leak = await bob.evaluate(() =>
        (window.raw || []).find((frame) => frame.includes('"type":"delta"') && frame.includes('"xp"')));
    !leak ? ok('no delta ever carried anybody\'s experience') : fail(`a broadcast delta carried xp: ${leak}`);

    // ---- dying puts you out of action ------------------------------------
    // A level-one character keeps attacking wolves until one wins. That it
    // eventually does is the design: the map is not safe.
    const deathsBefore = await ala.evaluate(() => window.deaths || 0);
    let died = false;
    const dieBy = Date.now() + 150_000;
    while (Date.now() < dieBy && !died) {
        const busy = await ala.evaluate(() => {
            const self = state.actors.get(state.selfId);
            return !!(self && self.inFight);
        });
        if (!busy) {
            const prey = await ala.evaluate(() =>
                [...state.actors.values()].find((a) => a.kind === 'MOB' && a.name === 'Wilk')
                || [...state.actors.values()].find((a) => a.kind === 'MOB') || null);
            if (prey) {
                await ala.evaluate((id) => state.ws.send(JSON.stringify({ type: 'attack', targetId: id })), prey.id);
            }
        }
        await ala.waitForTimeout(1_500);
        died = await ala.evaluate((before) => (window.deaths || 0) > before, deathsBefore);
    }

    if (died) {
        ok('dying knocked the character out');
        if (shots) await ala.screenshot({ path: `${shots}/smierc.png` });

        // The client is never told where the spawn is, so this uses the place a
        // freshly created character first stood - which is the spawn.
        const home = await ala.evaluate(() => window.atDeath || { at: [] });
        home.at[0] === start.x && home.at[1] === start.y
            ? ok('and put it back where it first appeared')
            : fail(`death left the character at ${home.at}, expected ${[start.x, start.y]}`);

        // And barely standing. Nothing in this game regenerates, so a death
        // that healed you was the only cure in it.
        const afterDeath = await ala.evaluate(() => ({ hp: state.you.hp, maxHp: state.you.maxHp }));
        afterDeath.hp === 1
            ? ok('and left it on one point of health rather than fully mended')
            : fail(`waking up gave ${afterDeath.hp}/${afterDeath.maxHp}, expected 1`);
    } else {
        fail('the character never died, or died without a penalty');
    }

    // ---- and while it is out, nothing it sends does anything -------------
    {
        const stillOut = await ala.evaluate(() => state.you.wakesAt > Date.now());
        if (!stillOut) {
            fail('the character was playable again immediately after dying');
        } else {
            ok('and it cannot be played until it comes round');

            const overlay = await ala.evaluate(() =>
                !document.querySelector('#knockout').hidden);
            overlay ? ok('the overlay says so') : fail('nothing on screen said why');
            await ala.screenshot({ path: 'ocknienie.png' });

            // Try to walk. The server should answer and nothing should move.
            const before = await ala.evaluate(() => {
                const self = state.actors.get(state.selfId);
                return { x: self.x, y: self.y };
            });
            await ala.evaluate(() => requestMove(state.map.width - 2, 1));
            await ala.waitForTimeout(2_500);
            const after = await ala.evaluate(() => {
                const self = state.actors.get(state.selfId);
                return { x: self.x, y: self.y };
            });
            after.x === before.x && after.y === before.y
                ? ok('walking is refused while it is out')
                : fail(`it walked from ${before.x},${before.y} to ${after.x},${after.y}`);

            // Chat is the one thing that still works, on purpose.
            await ala.evaluate(() => state.ws.send(JSON.stringify({
                type: 'chat', text: 'zaraz wracam' })));
            const spoke = await ala
                .waitForFunction(() => [...document.querySelectorAll('#chat-log *')]
                    .some((el) => el.textContent.includes('zaraz wracam')),
                    null, { timeout: 8_000 })
                .then(() => true).catch(() => false);
            spoke
                ? ok('but the player can still say so in chat')
                : fail('chat was refused too, which punishes the person not the character');

            // And it comes round on its own. At level one that is twenty seconds.
            const woke = await ala
                .waitForFunction(() => state.you.wakesAt === 0, null, { timeout: 120_000 })
                .then(() => true).catch(() => false);
            woke
                ? ok('it comes round on its own, with nothing sent to ask')
                : fail('it never came round');

            await ala.evaluate(() => requestMove(state.map.width - 2, 1));
            const movedAgain = await ala
                .waitForFunction((was) => {
                    const self = state.actors.get(state.selfId);
                    return self.x !== was.x || self.y !== was.y;
                }, after, { timeout: 15_000 })
                .then(() => true).catch(() => false);
            movedAgain
                ? ok('and then walking works again')
                : fail('it came round but still could not move');
        }
    }

    // ---- so the first thing anybody does next is go and get mended --------
    // This is the loop the game now has: fight, lose, walk to the herbalist,
    // fight again. Nothing below this line would work on one point of health,
    // which is the point of the whole milestone rather than a quirk of the
    // script's order.
    {
        const hurt = await ala.evaluate(() => ({ hp: state.you.hp, maxHp: state.you.maxHp }));
        hurt.hp < hurt.maxHp
            ? ok(`the character is hurt (${hurt.hp}/${hurt.maxHp}) and nothing mends on its own`)
            : fail('nothing hurt the character, so there is nothing to heal');

        const weakBefore = await ala.evaluate(() => state.you.weakenedUntil);
        const mended = await mendIfHurt(ala);
        mended
            ? ok('the herbalist put the character back together')
            : fail(`healing left ${await ala.evaluate(() => state.you.hp)} health`);

        // The wound is mended; the price of having died is not.
        const weakAfter = await ala.evaluate(() => state.you.weakenedUntil);
        weakAfter === weakBefore
            ? ok('and left the death penalty exactly where it was')
            : fail('healing moved the death penalty, which is not a wound');

        await ala.screenshot({ path: 'leczenie.png' });
    }

    // ---- loot, and putting it on -----------------------------------------
    // The boar drops a sword every time in this content, so the only question
    // here is whether the chain works end to end: a kill fills the bag, the
    // panel shows it, a click wears it, and wearing it changes the character.
    await ala.keyboard.press('i');
    const panelOpen = await ala.evaluate(() => !document.getElementById('panel').hidden);
    panelOpen ? ok('the character panel opens') : fail('the panel did not open');

    const carried = await ala.evaluate(() =>
        (state.bag && state.bag.carried.length ? state.bag.carried : null));

    if (!carried) {
        fail('nothing was ever looted, so there is nothing to wear');
    } else {
        ok(`the kill paid in goods: ${carried.map((item) => item.name).join(', ')}`);

        const wearable = carried.find((item) => item.wearable);
        if (!wearable) {
            fail('everything looted is above this level; cannot test wearing');
        } else {
            const before = await ala.evaluate(() => ({ attack: state.you.attack, armor: state.you.armor }));
            await ala.click(`#bag li[data-item-id="${wearable.id}"]`);

            const worn = await ala
                .waitForFunction((id) => (state.bag.worn || []).some((item) => item.id === id),
                    wearable.id, { timeout: 10_000 })
                .then(() => true)
                .catch(() => false);
            worn
                ? ok(`clicking it in the bag put it on: ${wearable.name}`)
                : fail('the item never moved from the bag to a slot');

            const after = await ala.evaluate(() => ({ attack: state.you.attack, armor: state.you.armor }));
            after.attack > before.attack || after.armor > before.armor
                ? ok(`and it changed the character: attack ${before.attack} -> ${after.attack}`)
                : fail(`wearing ${wearable.name} changed nothing: ${JSON.stringify(after)}`);

            if (shots) await ala.screenshot({ path: `${shots}/ekwipunek.png` });

            // Reload rather than merely reconnect: this is the whole round trip
            // through the database, which is where an item is most likely to be
            // quietly lost.
            await ala.reload();
            // A reload does not remember which character was being played, so
            // this goes back in through the selection screen exactly as a
            // person would.
            await ala.waitForSelector(`.character:has-text("${ALA}")`, { timeout: 15_000 });
            await ala.click(`.character:has-text("${ALA}")`);
            await ala.waitForFunction(() => state.selfId !== null, null, { timeout: 15_000 });
            const stillWorn = await ala
                .waitForFunction((id) => state.bag && (state.bag.worn || []).some((i) => i.id === id),
                    wearable.id, { timeout: 15_000 })
                .then(() => true)
                .catch(() => false);
            stillWorn
                ? ok('and it is still worn after reloading the page')
                : fail('the item came off, or vanished, across a reload');
        }
    }

    // ---- movement keys still work with the panel open --------------------
    // The chat box taught this lesson once already: a piece of interface that
    // quietly takes the keyboard makes the game feel broken, not busy.
    await ala.evaluate(() => { document.getElementById('panel').hidden = false; });
    const stepWithPanel = await tryStep(ala, STEP_MS);
    stepWithPanel
        ? ok('the character still walks with the panel open')
        : fail('the open panel swallowed the movement keys');

    // ---- energy fills, and buys something --------------------------------
    // The bar that replaced mana. Mana was always full and bought nothing; the
    // only interesting thing about energy is that it moves, so that is what
    // this watches.
    const startingEnergy = await ala.evaluate(() => state.you.energy);
    startingEnergy === 0
        ? ok('energy starts at nothing outside a fight')
        : fail(`energy was ${startingEnergy} before a single blow`);

    // Ala is a mage, so she spends her one point on her own skill.
    await ala.evaluate(() => state.ws.send(JSON.stringify({ type: 'learn', skillId: 'blyskawica' })));
    const learned = await ala
        .waitForFunction(() => (state.skills.skills || []).some((s) => s.id === 'blyskawica' && s.rank > 0),
            null, { timeout: 10_000 })
        .then(() => true)
        .catch(() => false);
    learned ? ok('a skill point buys a rank') : fail('the point never went in');

    let charged = false;
    let cast = false;
    const untilCast = Date.now() + 150_000;
    while (Date.now() < untilCast && !cast) {
        const busy = await ala.evaluate(() => {
            const self = state.actors.get(state.selfId);
            return !!(self && self.inFight);
        });
        if (!busy) {
            await mendIfHurt(ala, 0.4);
            // A wolf for preference: a boar is dead in three rounds, and a mage
            // cannot charge thirty energy in three rounds. That is the design
            // working - long fights suit skills, short ones suit swinging - but
            // it does mean this has to pick a fight that lasts.
            const prey = await ala.evaluate(() =>
                [...state.actors.values()].find((a) => a.kind === 'MOB' && a.name === 'Wilk')
                || [...state.actors.values()].find((a) => a.kind === 'MOB') || null);
            if (!prey) break;
            await ala.evaluate((id) => state.ws.send(JSON.stringify({ type: 'attack', targetId: id })), prey.id);
        }
        await ala.waitForTimeout(1_200);

        const seen = await ala.evaluate(() => ({
            energy: state.you.energy,
            inFight: !!(state.actors.get(state.selfId) || {}).inFight,
            bar: !document.getElementById('skillbar').hidden,
            ready: !!document.querySelector('#skillbar button:not([disabled])'),
        }));
        if (seen.energy > 0 && seen.inFight) charged = true;
        if (seen.bar && seen.ready) {
            if (shots) await ala.screenshot({ path: `${shots}/energia.png` });
            const before = seen.energy;
            // Clicked inside the page rather than through the driver: a fight
            // can end between resolving the button and pressing it, and then
            // the driver is aiming at an element that no longer exists.
            await ala.evaluate(() => {
                const button = document.querySelector('#skillbar button:not([disabled])');
                if (button) button.click();
            });
            cast = await ala
                .waitForFunction((was) => state.you.energy < was, before, { timeout: 8_000 })
                .then(() => true)
                .catch(() => false);
        }
    }

    charged
        ? ok('energy charges round by round once a fight starts')
        : fail('energy never moved during a fight');
    cast
        ? ok('a skill was thrown from the bar, and it cost energy')
        : fail('the skill never went off');

    // ---- and it is gone when the fight is --------------------------------
    await escapeAnyFight(ala);
    const afterwards = await ala
        .waitForFunction(() => state.you.energy === 0, null, { timeout: 15_000 })
        .then(() => true)
        .catch(() => false);
    afterwards
        ? ok('and the bar empties when the fight ends')
        : fail(`energy survived the fight: ${await ala.evaluate(() => state.you.energy)}`);

    // ---- somebody to talk to ---------------------------------------------
    await escapeAnyFight(ala);
    const herbalist = await ala.evaluate(() =>
        [...state.actors.values()].find((a) => a.kind === 'NPC') || null);

    if (!herbalist) {
        fail('there is nobody on the map to talk to');
    } else {
        ok(`${herbalist.name} is standing on the map`);

        // Clicked, not messaged: what is under test is that walking over and
        // asking on arrival works, which is the whole of the interface here.
        //
        // Kept asking rather than asked once. Crossing this map means being
        // attacked on the way, and a fight holds you where you stand - so a
        // single request proves nothing about whether walking up to somebody
        // works.
        let opened = false;
        const arriveBy = Date.now() + 90_000;
        while (Date.now() < arriveBy && !opened) {
            await escapeAnyFight(ala);
            await ala.evaluate((them) => {
                const spot = besideThem(them);
                state.walkingUpTo = them.id;
                requestMove(spot.x, spot.y);
            }, herbalist);
            opened = await ala
                .waitForFunction(() => {
                    const panel = document.querySelector('#dialogue');
                    return !panel.hidden
                        && document.querySelector('#dialogue-who').textContent.includes('Miłka');
                }, null, { timeout: 15_000 })
                .then(() => true)
                .catch(() => false);
        }
        opened
            ? ok('walking up to them opened the conversation')
            : fail('the conversation never opened');

        if (opened) {
            const first = await ala.textContent('#dialogue-text');
            // Picked by what it says. Written as :first-child this quietly
            // became "heal me" the day an option was added to the greeting.
            await ala.click('#dialogue-options button:text-is("Co tu rośnie?")');
            const moved = await ala
                .waitForFunction((was) =>
                    document.querySelector('#dialogue-text').textContent !== was,
                    first, { timeout: 8_000 })
                .then(() => true)
                .catch(() => false);
            moved
                ? ok('choosing an option led somewhere else in the tree')
                : fail('the conversation did not move');

            await ala.screenshot({ path: 'rozmowa.png' });

            // Walking off has to close it, and nothing is sent when it does.
            await ala.evaluate(() => requestMove(state.map.width - 2, 1));
            // waitForSelector waits for a *visible* element by default, so
            // asking it for the hidden window would wait for ever no matter
            // what the server did. Ask the property instead.
            const closed = await ala
                .waitForFunction(() => document.querySelector('#dialogue').hidden,
                    null, { timeout: 20_000 })
                .then(() => true)
                .catch(() => false);
            closed
                ? ok('walking away closed the conversation')
                : fail('the conversation outlived being walked away from');
        }
    }

    // ---- money, and the two kinds of it ----------------------------------
    await escapeAnyFight(ala);
    await mendIfHurt(ala);

    const purse = await ala.evaluate(() => state.purse && state.purse.coins);
    if (!purse || purse.length < 2) {
        fail(`the purse holds ${purse ? purse.length : 0} kind(s) of money; the design is plural`);
    } else {
        ok(`the purse knows about ${purse.map((c) => c.name).join(' and ')}`);
    }

    const gold = await ala.evaluate(() =>
        (state.purse.coins.find((c) => c.id === 'zloto') || {}).amount);
    gold > 0
        ? ok(`killing things paid ${gold} gold`)
        : fail('nothing ever dropped any money');

    // Earn what this section is going to spend. A character arriving at the
    // stall with an empty bag and four gold tests the weather, not the shop -
    // and which of those it is depends on what the phases above happened to
    // leave behind.
    const CHEAPEST = 18;
    const earnUntil = Date.now() + 150_000;
    while (Date.now() < earnUntil) {
        const rich = await ala.evaluate(() =>
            (state.bag.carried || []).length > 0
            || state.purse.coins.find((c) => c.id === 'zloto').amount >= 60);
        if (rich) break;
        const busy = await ala.evaluate(() => {
            const self = state.actors.get(state.selfId);
            return !!(self && self.inFight);
        });
        if (!busy) {
            await mendIfHurt(ala, 0.4);
            const prey = await ala.evaluate(() =>
                [...state.actors.values()].find((a) => a.kind === 'MOB') || null);
            if (!prey) break;
            await ala.evaluate((id) =>
                state.ws.send(JSON.stringify({ type: 'attack', targetId: id })), prey.id);
        }
        await ala.waitForTimeout(1_500);
    }

    // Enough for the cheapest thing on either shelf before any of this: what a
    // run earns is luck, and a check about trading should not be one about it.
    await earnAtLeast(ala, 60);

    // Walk to the trader who deals in gold and buy the cheapest thing there.
    const openStall = async (page, npcName) => {
        const them = await page.evaluate((name) =>
            [...state.actors.values()].find((a) => a.kind === 'NPC' && a.name.includes(name)) || null,
            npcName);
        if (!them) return false;

        // Shut whatever is open first, and wait for it to actually go. Waiting
        // on "#dialogue:not([hidden])" matched the conversation that was still
        // on screen from the previous section, so the opener was looked for in
        // the herbalist's options and never found.
        await page.evaluate(() => state.ws.send(JSON.stringify({ type: 'endTalk' })));
        await page.waitForFunction(() => document.querySelector('#dialogue').hidden,
            null, { timeout: 10_000 }).catch(() => {});

        // Walking across this map means being attacked on the way, and a fight
        // holds you where you stand - so this keeps asking rather than assuming
        // one request gets there.
        let opener = null;
        const arriveBy = Date.now() + 90_000;
        while (Date.now() < arriveBy && !opener) {
            await escapeAnyFight(page);
            await page.evaluate((t) => {
                const spot = besideThem(t);
                state.walkingUpTo = t.id;
                requestMove(spot.x, spot.y);
            }, them);
            opener = await page
                .waitForFunction(() => [...document.querySelectorAll('#dialogue-options button')]
                    .map((b) => b.textContent)
                    .find((t) => /Rozkładaj|Unieś wieko|na drogę/.test(t)) || null,
                    null, { timeout: 15_000 })
                .then((handle) => handle.jsonValue())
                .catch(() => null);
        }
        if (!opener) return false;

        await page.click(`#dialogue-options button:text-is("${opener}")`);
        return page.waitForFunction((name) => {
            const shop = document.querySelector('#shop');
            return !shop.hidden && document.querySelector('#shop-who').textContent.includes(name);
        }, npcName, { timeout: 10_000 }).then(() => true).catch(() => false);
    };

    if (!(await openStall(ala, 'Bartosz'))) {
        fail('the gold trader never opened a stall');
    } else {
        ok('the trader opened a stall');
        await ala.screenshot({ path: 'sklep.png' });

        // Sell the loot first. That is the loop this milestone created - kill,
        // sell what dropped, afford something better - and a character who has
        // only killed a few boars cannot buy anything without it.
        let sold = 0;
        while (sold < 8 && await ala.evaluate(() =>
                document.querySelector('#shop-sell li:not(.empty)') !== null)) {
            const was = await ala.evaluate(() =>
                state.purse.coins.find((c) => c.id === 'zloto').amount);
            await ala.click('#shop-sell li:not(.empty)');
            const paid = await ala
                .waitForFunction((w) => state.purse.coins
                    .find((c) => c.id === 'zloto').amount > w, was, { timeout: 8_000 })
                .then(() => true).catch(() => false);
            if (!paid) break;
            sold++;
        }
        const carriedSellable = await ala.evaluate(() =>
            document.querySelector('#shop-sell li:not(.empty)') !== null);
        if (sold > 0) {
            ok(`sold ${sold} piece(s) of loot for coin`);
        } else if (carriedSellable) {
            fail('the trader refused loot they had on their own shelf');
        } else {
            ok('nothing in the bag was this trader\'s business, which is the rule working');
        }

        const purseNow = await ala.evaluate(() =>
            state.purse.coins.find((c) => c.id === 'zloto').amount);
        const affordable = await ala.evaluate(() =>
            [...document.querySelectorAll('#shop-goods li:not(.too-dear)')]
                .map((li) => li.querySelector('span').textContent));
        if (!affordable.length) {
            fail(`nothing on the shelf is affordable on ${purseNow} gold,`
                + ` and the cheapest thing costs ${CHEAPEST}`);
        } else {
            const goldBefore = purseNow;
            const carriedBefore = await ala.evaluate(() => state.bag.carried.length);
            await ala.click('#shop-goods li:not(.too-dear)');

            const bought = await ala
                .waitForFunction((was) => state.bag.carried.length > was, carriedBefore,
                    { timeout: 10_000 })
                .then(() => true).catch(() => false);
            bought ? ok(`bought ${affordable[0]}`) : fail('the purchase never arrived');

            const goldAfter = await ala.evaluate(() =>
                state.purse.coins.find((c) => c.id === 'zloto').amount);
            goldAfter < goldBefore
                ? ok(`and it cost ${goldBefore - goldAfter} gold`)
                : fail('the purchase was free');

            // Sell it straight back. The round trip has to lose money, or loot
            // is worth nothing and gold never leaves the world.
            const sellable = await ala.evaluate(() =>
                document.querySelector('#shop-sell li:not(.empty)') !== null);
            if (!sellable) {
                fail('the trader will not buy back what they just sold');
            } else {
                await ala.click('#shop-sell li:not(.empty)');
                const returned = await ala
                    .waitForFunction((was) => state.purse.coins
                        .find((c) => c.id === 'zloto').amount !== was, goldAfter,
                        { timeout: 10_000 })
                    .then(() => true).catch(() => false);
                const goldBack = await ala.evaluate(() =>
                    state.purse.coins.find((c) => c.id === 'zloto').amount);
                returned && goldBack < goldBefore
                    ? ok(`selling it back returned ${goldBack - goldAfter}, so the round trip`
                        + ` cost ${goldBefore - goldBack} - the only way money leaves this world`)
                    : fail(`a round trip through the shop cost nothing: ${goldBefore} -> ${goldBack}`);
            }
        }
    }

    // ---- and the whole reason money is plural ---------------------------
    await ala.evaluate(() => state.ws.send(JSON.stringify({ type: 'endTalk' })));
    if (!(await openStall(ala, 'Skrzynia'))) {
        fail('the chest never opened');
    } else {
        const outOfReach = await ala.evaluate(() => ({
            currency: state.shop.currencyId,
            gold: (state.purse.coins.find((c) => c.id === 'zloto') || {}).amount,
            fangs: (state.purse.coins.find((c) => c.id === 'kly') || {}).amount,
            greyed: document.querySelectorAll('#shop-goods li.too-dear').length,
            total: document.querySelectorAll('#shop-goods li').length,
        }));
        outOfReach.currency === 'kly'
            ? ok('the chest deals in fangs, not gold')
            : fail(`the chest deals in ${outOfReach.currency}`);

        if (outOfReach.fangs < 1) {
            outOfReach.greyed === outOfReach.total
                ? ok(`and ${outOfReach.gold} gold buys none of it - which is what a purse`
                    + ' in the plural is for')
                : fail('gold bought something from a trader who does not take gold');
        } else {
            ok(`and the ${outOfReach.fangs} fang(s) that dropped are spendable only here`);
        }
        await ala.screenshot({ path: 'skrzynia.png' });
        await ala.evaluate(() => state.ws.send(JSON.stringify({ type: 'endTalk' })));
    }

    // ---- something to drink out there ------------------------------------
    await ala.evaluate(() => state.ws.send(JSON.stringify({ type: 'endTalk' })));
    await escapeAnyFight(ala);

    if (!(await openStall(ala, 'Miłka'))) {
        fail('the herbalist never opened a stall');
    } else {
        ok('the herbalist heals and sells - one NPC, two functions');

        const shelf = await ala.evaluate(() => (state.shop.goods || [])
            .map((g) => ({ defId: g.defId, price: g.price, restores: g.restores, holds: g.holds })));
        const potions = shelf.filter((g) => g.restores);
        potions.length === shelf.length && potions.length > 0
            ? ok(`her shelf is all bottles: ${potions.map((p) => p.restores).join(', ')}`)
            : fail(`the herbalist is selling something that is not a bottle: ${JSON.stringify(shelf)}`);

        const cheapest = potions.sort((a, b) => a.price - b.price)[0];
        let gold = await ala.evaluate(() =>
            state.purse.coins.find((c) => c.primary).amount);
        if (gold < cheapest.price) {
            // Go and earn it, the way a player would. Leaving her closes the
            // stall, so it is opened again afterwards.
            await ala.evaluate(() => state.ws.send(JSON.stringify({ type: 'endTalk' })));
            await earnAtLeast(ala, cheapest.price);
            gold = await ala.evaluate(() =>
                state.purse.coins.find((c) => c.primary).amount);
            if (!(await openStall(ala, 'Miłka'))) {
                fail('the herbalist would not open her stall a second time');
            }
        }
        const buying = gold >= cheapest.price ? cheapest : null;

        if (!buying) {
            fail(`nothing on the herbalist's shelf is affordable with ${gold} gold`);
        } else {
            await ala.click(`#shop-goods li:has-text("${potions.find((p) => p.defId === buying.defId).restores}")`)
                .catch(async () => {
                    await ala.evaluate((defId) => state.ws.send(JSON.stringify({
                        type: 'buy', itemId: defId,
                    })), buying.defId);
                });
            const bought = await ala
                .waitForFunction((defId) => (state.bag.carried || [])
                    .some((i) => i.defId === defId), buying.defId, { timeout: 10_000 })
                .then(() => true).catch(() => false);
            bought
                ? ok(`bought a bottle for ${buying.price} gold`)
                : fail('the bottle never reached the bag');

            await ala.evaluate(() => state.ws.send(JSON.stringify({ type: 'endTalk' })));

            // Hurt, away from her, and then mended by what was bought. That is
            // the whole point: health that travels.
            const wounded = await (async () => {
                const until = Date.now() + 120_000;
                while (Date.now() < until) {
                    const state0 = await ala.evaluate(() => ({
                        hp: state.you.hp,
                        maxHp: state.you.maxHp,
                        inFight: !!(state.actors.get(state.selfId) || {}).inFight,
                    }));
                    if (state0.hp < state0.maxHp) {
                        // Out of it the moment there is a wound to mend. A
                        // fight picked and then left running is a fight this
                        // character loses, and a dead one proves nothing about
                        // bottles.
                        await escapeAnyFight(ala);
                        await waitIfOut(ala);
                        return await ala.evaluate(() => state.you.hp < state.you.maxHp);
                    }
                    if (!state0.inFight) {
                        const prey = await ala.evaluate(() =>
                            [...state.actors.values()].find((a) => a.kind === 'MOB') || null);
                        if (!prey) return false;
                        await ala.evaluate((id) => state.ws.send(JSON.stringify({
                            type: 'attack', targetId: id,
                        })), prey.id);
                    }
                    await ala.waitForTimeout(1_500);
                }
                return false;
            })();
            if (!wounded) {
                fail('nothing ever landed a blow, so there is no wound to mend');
            } else {
                // A bottle is refused in a fight, which is half the rule.
                const inFight = await ala.evaluate(() =>
                    !!(state.actors.get(state.selfId) || {}).inFight);
                if (inFight) {
                    const potion = await ala.evaluate((defId) => (state.bag.carried || [])
                        .find((i) => i.defId === defId) || null, buying.defId);
                    await ala.evaluate((id) => state.ws.send(JSON.stringify({
                        type: 'drink', itemId: id,
                    })), potion.id);
                    const told = await ala
                        .waitForFunction(() => [...document.querySelectorAll('#chat-log li')]
                            .map((li) => li.textContent)
                            .some((t) => /W walce nie ma na to czasu/.test(t)),
                            null, { timeout: 10_000 })
                        .then(() => true).catch(() => false);
                    told
                        ? ok('and a bottle is refused in the middle of a fight')
                        : fail('drinking in a fight was not refused');
                }
                await escapeAnyFight(ala);

                const before = await ala.evaluate(() => state.you.hp);
                const potion = await ala.evaluate((defId) => (state.bag.carried || [])
                    .find((i) => i.defId === defId) || null, buying.defId);
                await ala.screenshot({ path: 'mikstura.png' });
                await ala.evaluate((id) => state.ws.send(JSON.stringify({
                    type: 'drink', itemId: id,
                })), potion.id);
                const mended = await ala
                    .waitForFunction((was) => state.you.hp > was, before, { timeout: 10_000 })
                    .then(() => true).catch(() => false);
                const after = await ala.evaluate(() => state.you.hp);
                mended
                    ? ok(`drinking it out here gave back ${after - before} health,`
                        + ' with no herbalist in sight')
                    : fail(`the bottle did nothing: ${before} -> ${after}`);

                const left = await ala.evaluate((id) => {
                    const still = (state.bag.carried || []).find((i) => i.id === id);
                    return still ? still.remaining || 'full' : null;
                }, potion.id);
                buying.holds
                    ? (typeof left === 'number' && left < buying.holds
                        ? ok(`and the flask is down to ${left} of ${buying.holds}`)
                        : fail(`a flask holding ${buying.holds} came back as ${left}`))
                    : (left === null
                        ? ok('and a one-mouthful bottle is gone')
                        : fail(`a bottle with no pool survived being drunk: ${left}`));
            }
        }
    }

    // ---- what a skill point costs, and where it costs less ---------------
    await ala.evaluate(() => state.ws.send(JSON.stringify({ type: 'endTalk' })));
    await escapeAnyFight(ala);

    const myClass = await ala.evaluate(() => state.you.classId);
    const masterNames = { wojownik: 'Mistrz Miecza', lowca: 'Tropicielka', mag: 'Magister Run' };
    const myMaster = masterNames[myClass];

    const fullPrice = await ala.evaluate(() => state.you.skillPointPrice);
    fullPrice > 0
        ? ok(`a skill point costs ${fullPrice} from the panel`)
        : fail('a skill point costs nothing from the panel');

    // Walk to this character's own master and watch the price fall on its own.
    const walkTo = async (page, name) => {
        const them = await page.evaluate((who) =>
            [...state.actors.values()].find((a) => a.kind === 'NPC' && a.name.includes(who)) || null,
            name);
        if (!them) return false;
        // The masters stand far apart on purpose, so this is a real walk across
        // a map that hunts back - and a character on one point of health loses
        // every fight it is dragged into on the way.
        const arriveBy = Date.now() + 180_000;
        while (Date.now() < arriveBy) {
            await escapeAnyFight(page);
            await mendIfHurt(page, 0.5);
            await page.evaluate((t) => {
                const spot = besideThem(t);
                state.walkingUpTo = t.id;
                requestMove(spot.x, spot.y);
            }, them);
            const there = await page
                .waitForFunction((who) => {
                    const panel = document.querySelector('#dialogue');
                    return !panel.hidden
                        && document.querySelector('#dialogue-who').textContent.includes(who);
                }, name, { timeout: 15_000 })
                .then(() => true).catch(() => false);
            if (there) return true;
        }
        return false;
    };

    if (!(await walkTo(ala, myMaster))) {
        fail(`${myMaster} never opened a conversation`);
    } else {
        ok(`walked up to ${myMaster}, who keeps the ${myClass} class`);

        // Only that it dropped. What it dropped *from* is checked further down,
        // by stepping away again: the price scales with level, and the walk
        // over here is a walk through things that fight back - so the number
        // read before setting off is not necessarily this character's any more.
        const atMaster = await ala
            .waitForFunction((was) => state.you.skillPointPrice < was, fullPrice, { timeout: 8_000 })
            .then(() => true).catch(() => false);
        const discounted = await ala.evaluate(() => state.you.skillPointPrice);
        atMaster
            ? ok(`and standing there drops it from ${fullPrice} to ${discounted},`
                + ' with nothing sent to ask')
            : fail(`the price at the master is ${discounted}, no lower than ${fullPrice}`);

        await ala.screenshot({ path: 'mistrz.png' });

        // Spend one here, then have it given back.
        const pointsBefore = await ala.evaluate(() => state.you.skillPoints);
        const goldBefore = await ala.evaluate(() =>
            state.purse.coins.find((c) => c.primary).amount);
        if (pointsBefore > 0 && goldBefore >= discounted) {
            await ala.keyboard.press('i');
            await ala.evaluate(() => {
                const learnable = (state.skills.skills || []).find((s) => s.rank > 0
                    && s.rank < s.maxRank);
                if (learnable) state.ws.send(JSON.stringify({ type: 'learn', skillId: learnable.id }));
            });
            const spent = await ala
                .waitForFunction((was) => state.you.skillPoints < was, pointsBefore,
                    { timeout: 8_000 })
                .then(() => true).catch(() => false);
            const goldAfter = await ala.evaluate(() =>
                state.purse.coins.find((c) => c.primary).amount);
            spent && goldAfter === goldBefore - discounted
                ? ok(`spent a point here for ${goldBefore - goldAfter}, not ${fullPrice}`)
                : fail(`spending a point at the master cost ${goldBefore - goldAfter}`);
        } else {
            ok(`no spare point or coin to spend here (${pointsBefore} point(s),`
                + ` ${goldBefore} gold) - nothing to prove about the discount twice`);
        }

        // And the only thing a master really exists for.
        const beforeReset = await ala.evaluate(() => ({
            points: state.you.skillPoints,
            ranks: (state.skills.skills || []).reduce((sum, s) => sum + s.rank, 0),
        }));
        const resetPrice = await ala.evaluate(() => state.you.skillResetPrice);
        const purse = await ala.evaluate(() =>
            state.purse.coins.find((c) => c.primary).amount);
        if (beforeReset.ranks === 0) {
            fail('nothing was ever learned, so there is nothing to take back');
        } else if (purse < resetPrice) {
            // Spending a point here a moment ago is what emptied the purse, and
            // a master who undid the work for free would be the thing this
            // whole section says he is not.
            ok(`starting over costs ${resetPrice} and there is ${purse} left,`
                + ' so the master refuses - which is the price being real');
        } else {
            await ala.click('#dialogue-options button:text-is("Chcę zacząć od nowa.")');
            await ala.waitForFunction(() => [...document.querySelectorAll('#dialogue-options button')]
                .some((b) => b.textContent.includes('Zwróć mi punkty')), null, { timeout: 8_000 });
            await ala.click('#dialogue-options button:text-is("Zwróć mi punkty.")');

            const undone = await ala
                .waitForFunction((was) => state.you.skillPoints > was, beforeReset.points,
                    { timeout: 8_000 })
                .then(() => true).catch(() => false);
            const after = await ala.evaluate(() => ({
                points: state.you.skillPoints,
                ranks: (state.skills.skills || []).reduce((sum, s) => sum + s.rank, 0),
            }));
            undone && after.ranks === 0 && after.points === beforeReset.points + beforeReset.ranks
                ? ok(`the master gave back all ${beforeReset.ranks} point(s) and cleared the ranks`)
                : fail(`after the reset: ${after.points} point(s), ${after.ranks} rank(s)`);
        }
        await ala.evaluate(() => state.ws.send(JSON.stringify({ type: 'endTalk' })));

        // And leaving puts it back up - measured here, a step away and a moment
        // later, so both numbers belong to the same character at the same level.
        await ala.evaluate(() => requestMove(state.you.x, state.you.y + 1));
        const rose = await ala
            .waitForFunction((was) => state.you.skillPointPrice > was, discounted,
                { timeout: 10_000 })
            .then(() => true).catch(() => false);
        const fullHere = await ala.evaluate(() => state.you.skillPointPrice);
        rose && discounted === Math.round(fullHere / 2)
            ? ok(`the master's price is exactly half the panel's: ${discounted} against ${fullHere}`)
            : fail(`away from the master a point costs ${fullHere}, against ${discounted} at it`);
    }

    // ---- the second container, and what stays in it ----------------------
    await ala.evaluate(() => state.ws.send(JSON.stringify({ type: 'endTalk' })));
    await escapeAnyFight(ala);

    if (!(await walkTo(ala, 'Otton'))) {
        fail('the storekeeper never opened a conversation');
    } else {
        ok('walked up to the storekeeper');
        const opener = await ala
            .waitForFunction(() => [...document.querySelectorAll('#dialogue-options button')]
                .map((b) => b.textContent).find((t) => /Otwieraj/.test(t)) || null,
                null, { timeout: 10_000 })
            .then((handle) => handle.jsonValue()).catch(() => null);
        if (!opener) {
            fail('the storekeeper offered no way into the chest');
        } else {
            await ala.click(`#dialogue-options button:text-is("${opener}")`);
            const opened = await ala
                .waitForFunction(() => !document.querySelector('#storage').hidden,
                    null, { timeout: 10_000 })
                .then(() => true).catch(() => false);
            opened ? ok('the chest opened') : fail('the chest never opened');

            const chests = await ala.evaluate(() =>
                (state.storage.chests || []).map((c) => c.scope));
            chests.includes('character') && chests.includes('account')
                ? ok('with both shelves on it: the character\'s own and the account\'s')
                : fail(`the chest showed ${JSON.stringify(chests)}`);

            // By this point in the run everything loose has usually been sold,
            // so the bag is empty. Taking off what is worn puts something back
            // in it - and makes this check decisive rather than skipped.
            let carried = await ala.evaluate(() =>
                (state.bag.carried || []).map((i) => ({ id: i.id, name: i.name })));
            if (!carried.length) {
                const worn = await ala.evaluate(() => (state.bag.worn || [])[0] || null);
                if (worn) {
                    await ala.evaluate((slot) => state.ws.send(JSON.stringify({
                        type: 'unequip', slot,
                    })), worn.slot);
                    await ala.waitForFunction((id) =>
                        (state.bag.carried || []).some((i) => i.id === id),
                        worn.id, { timeout: 10_000 }).catch(() => {});
                    carried = await ala.evaluate(() =>
                        (state.bag.carried || []).map((i) => ({ id: i.id, name: i.name })));
                }
            }
            if (!carried.length) {
                fail('nothing could be found to put in the chest');
            } else {
                const putting = carried[0];
                await ala.evaluate((id) => state.ws.send(JSON.stringify({
                    type: 'deposit', itemId: id, tab: 0, account: false,
                })), putting.id);
                const stored = await ala
                    .waitForFunction((id) => !(state.bag.carried || []).some((i) => i.id === id)
                        && (state.storage.chests || [])
                            .some((c) => c.scope === 'character'
                                && (c.items || []).some((k) => k.item.id === id)),
                        putting.id, { timeout: 10_000 })
                    .then(() => true).catch(() => false);
                stored
                    ? ok(`${putting.name} left the bag and is on the shelf`)
                    : fail(`${putting.name} never made it into the chest`);
                await ala.screenshot({ path: 'sklad.png' });

                await ala.evaluate((id) => state.ws.send(JSON.stringify({
                    type: 'withdraw', itemId: id, account: false,
                })), putting.id);
                const back = await ala
                    .waitForFunction((id) => (state.bag.carried || []).some((i) => i.id === id),
                        putting.id, { timeout: 10_000 })
                    .then(() => true).catch(() => false);
                back
                    ? ok('and comes back out again, the same copy')
                    : fail('what went into the chest could not be taken back out');
            }
        }
        await ala.evaluate(() => state.ws.send(JSON.stringify({ type: 'endTalk' })));
    }

    // ---- a party, across two maps -----------------------------------------
    // Bob has been standing on the glade this whole run. The claim worth
    // proving is the one the design exists for: Ala walks into the wood and
    // Bob still sees how she is doing, on a panel no map's tick ever built.
    await ala.evaluate(() => state.ws.send(JSON.stringify({ type: 'endTalk' })));

    await ala.fill('#party-name', BOB);
    await ala.click('#party-ask');
    const offered = await bob
        .waitForFunction(() => !document.querySelector('#invite').hidden,
            null, { timeout: 15_000 })
        .then(() => true).catch(() => false);
    offered
        ? ok('the invitation reached the other tab')
        : fail('the invitation never arrived');

    if (offered) {
        await bob.screenshot({ path: 'zaproszenie.png' });
        await bob.click('#invite-yes');
        const joined = await ala
            .waitForFunction(() => state.party && state.party.members.length === 2,
                null, { timeout: 15_000 })
            .then(() => true).catch(() => false);
        joined
            ? ok('accepting put both of them in one party')
            : fail('the party never grew to two');

        const leader = await ala.evaluate(() =>
            (state.party.members.find((m) => m.leader) || {}).name);
        leader === ALA
            ? ok(`${ALA} leads it, being the one who asked`)
            : fail(`the party is led by ${leader}`);

        // A line said to the party, heard on the other tab and nowhere else.
        await ala.evaluate(() => state.ws.send(JSON.stringify({
            type: 'partyChat', text: 'idziemy do lasu',
        })));
        const heard = await bob
            .waitForFunction(() => [...document.querySelectorAll('#chat-log li')]
                .some((li) => li.textContent.includes('idziemy do lasu')),
                null, { timeout: 15_000 })
            .then(() => true).catch(() => false);
        heard
            ? ok('and a line said to the party reached the other tab')
            : fail('party chat did not arrive');

        await ala.screenshot({ path: 'druzyna.png' });
    }

    // ---- through the door, and back --------------------------------------
    await ala.evaluate(() => state.ws.send(JSON.stringify({ type: 'endTalk' })));
    await escapeAnyFight(ala);
    await mendIfHurt(ala);

    const homeMap = await ala.evaluate(() => state.map.id);
    // The free one. Picking doors[0] made the order inside a JSON file a rule
    // nobody had written down, and the wood has two of them now.
    const doorHere = await ala.evaluate(() =>
        (state.map.doors || []).find((d) => !d.takes) || null);

    if (!doorHere) {
        fail('the starting map has no way out of it');
    } else {
        ok(`${homeMap} has a door to ${doorHere.name}`);

        // Walked onto, not messaged: stepping on the tile is the whole
        // interaction, and that is what has to work.
        const crossed = await (async () => {
            const until = Date.now() + 180_000;
            while (Date.now() < until) {
                await escapeAnyFight(ala);
                await mendIfHurt(ala, 0.5);
                await ala.evaluate((d) => requestMove(d.x, d.y), doorHere);
                const there = await ala
                    .waitForFunction((was) => state.map.id !== was, homeMap, { timeout: 20_000 })
                    .then(() => true).catch(() => false);
                if (there) return true;
            }
            return false;
        })();

        if (!crossed) {
            // Said out loud, because "never led anywhere" on its own has twice
            // now cost a run without saying whether the character was stuck in
            // a fight, standing on the tile, or somewhere else entirely.
            const stuck = await ala.evaluate((d) => {
                const self = state.actors.get(state.selfId);
                return {
                    at: self ? `${self.x},${self.y}` : 'nowhere',
                    want: `${d.x},${d.y}`,
                    inFight: !!(self && self.inFight),
                    hp: state.you.hp,
                    wakesAt: state.you.wakesAt,
                    map: state.map.id,
                    lastLines: [...document.querySelectorAll('#chat-log li')]
                        .slice(-4).map((li) => li.textContent),
                };
            }, doorHere);
            fail(`walking onto the door never led anywhere: ${JSON.stringify(stuck)}`);
        } else {
            const wood = await ala.evaluate(() => ({
                id: state.map.id,
                name: state.map.name,
                creatures: [...state.actors.values()]
                    .filter((a) => a.kind === 'MOB').map((a) => a.name),
            }));
            ok(`stepping on it arrived in ${wood.name}`);
            wood.creatures.some((name) => !['Dzik', 'Wilk', 'Wilczyca Watahy'].includes(name))
                ? ok(`with creatures the glade does not have: ${[...new Set(wood.creatures)].join(', ')}`)
                : fail(`the wood is full of glade creatures: ${wood.creatures.join(', ')}`);

            await ala.screenshot({ path: 'las.png' });

            // Everything came along. A transfer that lost the purse would look
            // exactly like a working one until somebody tried to buy something.
            const carried = await ala.evaluate(() => ({
                hp: state.you.hp,
                gold: (state.purse.coins.find((c) => c.primary) || {}).amount,
                bag: (state.bag.carried || []).length,
            }));
            typeof carried.gold === 'number' && carried.hp > 0
                ? ok(`and the character came with it: ${carried.hp} hp, ${carried.gold} gold,`
                    + ` ${carried.bag} thing(s) in the bag`)
                : fail('something was left on the other side');

            // The new map is the one that listens now - the whole point of the
            // routing fix that went in before any of this.
            const walked = await (async () => {
                const before = await ala.evaluate(() => {
                    const self = state.actors.get(state.selfId);
                    return { x: self.x, y: self.y };
                });
                // A tile that is open on this map. Sent at a wall, the server
                // refuses and the test learns nothing about routing.
                await ala.evaluate(() => requestMove(6, 9));
                return ala
                    .waitForFunction((was) => {
                        const self = state.actors.get(state.selfId);
                        return self.x !== was.x || self.y !== was.y;
                    }, before, { timeout: 15_000 })
                    .then(() => true).catch(() => false);
            })();
            walked
                ? ok('and commands sent afterwards reach the map it is actually on')
                : fail('the character could not move on the new map');

            // The party panel does not care which map anybody is on - and this
            // is the only place in the game where that claim can be seen.
            const fromBob = await bob
                .waitForFunction((who) => {
                    if (!state.party) return false;
                    const her = state.party.members.find((m) => m.name === who);
                    return her && her.mapName === 'Las Wilczy' ? her.mapName : false;
                }, ALA, { timeout: 20_000 })
                .then((handle) => handle.jsonValue()).catch(() => null);
            fromBob
                ? ok(`and Bob's panel says she is in ${fromBob}, from the glade`)
                : fail("the party panel did not follow her to the other map");
            await bob.screenshot({ path: 'druzyna-mapy.png' });

            // ---- the passage that costs something --------------------------
            const gate = await ala.evaluate(() =>
                (state.map.doors || []).find((d) => d.takes) || null);
            if (!gate) {
                fail('the wood has no passage that costs anything');
            } else {
                ok(`the wood has a passage that burns something: ${gate.name}`
                    + ` — zabierze: ${gate.takes}`);

                const level = await ala.evaluate(() => state.you.level);
                const torch = await ala.evaluate((takes) =>
                    (state.bag.carried || []).find((i) => i.name === takes) || null, gate.takes);

                // Walking across the wood means being attacked on the way, so
                // this keeps asking rather than assuming one request arrives.
                const reachedIt = await (async () => {
                    const until = Date.now() + 120_000;
                    while (Date.now() < until) {
                        await escapeAnyFight(ala);
                        await mendIfHurt(ala, 0.5);
                        await ala.evaluate((d) => requestMove(d.x, d.y), gate);
                        const answered = await ala
                            .waitForFunction((d) => {
                                if (state.passage) return 'asked';
                                const self = state.actors.get(state.selfId);
                                const onIt = self && self.x === d.x && self.y === d.y;
                                const told = [...document.querySelectorAll('#chat-log li')]
                                    .map((li) => li.textContent)
                                    .some((t) => /otwiera si\u0119 od|Potrzebujesz/.test(t));
                                return told ? 'refused' : (onIt ? 'standing' : null);
                            }, gate, { timeout: 20_000 })
                            .then((handle) => handle.jsonValue()).catch(() => null);
                        if (answered === 'asked' || answered === 'refused') return answered;
                    }
                    return null;
                })();
                const asked = reachedIt === 'asked';

                if (!asked) {
                    // Under the threshold, which is the usual case for a
                    // character this young: the server refuses before it asks,
                    // and the refusal is the thing being checked.
                    const refused = await ala.evaluate(() =>
                        [...document.querySelectorAll('#chat-log li')]
                            .map((li) => li.textContent)
                            .some((t) => /otwiera si\u0119 od/.test(t)));
                    await ala.screenshot({ path: 'przejscie.png' });
                    refused && level < 6
                        ? ok(`a level ${level} character is turned away from it, and told why`)
                        : fail(`the passage neither asked nor explained itself (level ${level},`
                            + ` torch: ${!!torch}, outcome: ${reachedIt})`);

                    // The window itself, with the real button and the real
                    // socket. A young character cannot reach the far side of
                    // this passage, but the yes it sends is the same yes - and
                    // the server answering "you are not standing in it" is the
                    // check that the question is never taken on trust.
                    await ala.evaluate((d) => applyPassage({
                        type: 'passage', x: d.x, y: d.y, name: d.name, takes: d.takes,
                    }), gate);
                    const shown = await ala.evaluate(() =>
                        !document.querySelector('#passage').hidden
                        && document.querySelector('#passage-text').textContent);
                    shown && shown.includes(gate.takes)
                        ? ok(`the question names what it costs: "${shown}"`)
                        : fail('the passage window said nothing about the cost');
                    await ala.screenshot({ path: 'przejscie.png' });

                    await ala.click('#passage-go');
                    const answered = await ala
                        .waitForFunction(() => [...document.querySelectorAll('#chat-log li')]
                            .map((li) => li.textContent)
                            .some((t) => /Nie stoisz w tym przej\u015bciu|otwiera si\u0119 od/
                                .test(t)), null, { timeout: 10_000 })
                        .then(() => true).catch(() => false);
                    answered
                        ? ok('and a yes sent from the wrong tile is refused by the server')
                        : fail('a yes from the wrong tile was not answered at all');
                    await ala.evaluate(() => applyPassage({}));
                } else {
                    ok('stepping into it asks first rather than burning anything');
                    const stillCarried = await ala.evaluate((takes) =>
                        (state.bag.carried || []).some((i) => i.name === takes), gate.takes);
                    stillCarried || !torch
                        ? ok('and nothing has been spent while the question is open')
                        : fail('the ticket went before the answer did');
                    await ala.screenshot({ path: 'przejscie.png' });

                    if (torch) {
                        await ala.click('#passage-go');
                        const inside = await ala
                            .waitForFunction((was) => state.map.id !== was, 'las',
                                { timeout: 20_000 })
                            .then(() => true).catch(() => false);
                        const left = await ala.evaluate((takes) =>
                            (state.bag.carried || []).some((i) => i.name === takes), gate.takes);
                        inside && !left
                            ? ok(`saying yes opened it and burned the ${gate.takes}`)
                            : fail(`after saying yes: inside=${inside}, ticket left=${left}`);
                        // Back out the way we came in, so the rest of the run
                        // starts where it expects to.
                        const out = await ala.evaluate(() =>
                            (state.map.doors || []).find((d) => !d.takes) || null);
                        if (out) {
                            await ala.evaluate((d) => requestMove(d.x, d.y), out);
                            await ala.waitForFunction(() => state.map.id === 'las',
                                null, { timeout: 20_000 }).catch(() => {});
                        }
                    } else {
                        await ala.click('#passage-stay');
                        ok('and saying no leaves everything where it was');
                    }
                }
            }

            // And home again through the other door.
            const doorBack = await ala.evaluate(() =>
                (state.map.doors || []).find((d) => !d.takes) || null);
            const home = await (async () => {
                const until = Date.now() + 180_000;
                while (Date.now() < until) {
                    await escapeAnyFight(ala);
                    await mendIfHurt(ala, 0.5);
                    await ala.evaluate((d) => requestMove(d.x, d.y), doorBack);
                    const back = await ala
                        .waitForFunction((want) => state.map.id === want, homeMap, { timeout: 20_000 })
                        .then(() => true).catch(() => false);
                    if (back) return true;
                }
                return false;
            })();
            home
                ? ok(`and the door back leads to ${homeMap}`)
                : fail('there was no way home');
        }
    }

    // ---- a dropped socket resumes the same character ---------------------
    // Read now rather than at the start of the run. An actor id belongs to the
    // map that issued it, so a character that has been through a door has a
    // different one - and comparing against the first would be testing that
    // nobody travelled rather than that a reconnect resumes.
    const previousActor = await ala.evaluate(() => state.selfId);
    await ala.evaluate(() => state.ws.close());
    await ala.waitForTimeout(2_500);
    const resumed = await snapshot(ala);

    resumed.selfId === previousActor
        ? ok(`reconnect resumed actor ${previousActor} rather than spawning a new one`)
        : fail(`reconnect produced actor ${resumed.selfId}, expected ${previousActor}`);
} finally {
    await browser.close();
}

console.log(failures ? `\n${failures} check(s) failed` : '\nall checks passed');
process.exit(failures ? 1 : 0);
