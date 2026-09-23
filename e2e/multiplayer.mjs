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
        // Weakness lasts a minute, so "are you weakened" cannot tell a death
        // that just happened from one two phases ago. Watch for the moment it
        // changes instead, and note where the character stood right then.
        window.deaths = 0;
        let lastWeakened = 0;
        setInterval(() => {
            if (!state.you || state.you.weakenedUntil <= lastWeakened) return;
            lastWeakened = state.you.weakenedUntil;
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
async function escapeAnyFight(page) {
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
async function tryStep(page, stepMs) {
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
    await ala.mouse.click(200, 200);
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
    const huntUntil = Date.now() + 150_000;
    while (Date.now() < huntUntil && !(killed && looted)) {
        // No fleeing here: a fight already under way is as likely to end in a
        // kill as a fresh one, and running from it wastes a round every time.
        const busy = await ala.evaluate(() => {
            const self = state.actors.get(state.selfId);
            return !!(self && self.inFight);
        });
        if (!busy) {
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

    // ---- dying sends you home weakened -----------------------------------
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
        ok('dying left the character weakened');
        if (shots) await ala.screenshot({ path: `${shots}/smierc.png` });

        // The client is never told where the spawn is, so this uses the place a
        // freshly created character first stood - which is the spawn.
        const home = await ala.evaluate(() => window.atDeath || { at: [] });
        home.at[0] === start.x && home.at[1] === start.y
            ? ok('and put it back where it first appeared')
            : fail(`death left the character at ${home.at}, expected ${[start.x, start.y]}`);
    } else {
        fail('the character never died, or died without a penalty');
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
    const untilCast = Date.now() + 90_000;
    while (Date.now() < untilCast && !cast) {
        const busy = await ala.evaluate(() => {
            const self = state.actors.get(state.selfId);
            return !!(self && self.inFight);
        });
        if (!busy) {
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

    // ---- a dropped socket resumes the same character ---------------------
    const previousActor = alaView.selfId;
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
