/**
 * Drives two real browser tabs against a running server and checks the things
 * unit tests cannot: that two people actually see each other move, that the
 * server refuses an illegal destination, and that a dropped socket resumes the
 * same character instead of spawning a new one.
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

let failures = 0;
const ok = (message) => console.log(`ok   - ${message}`);
const fail = (message) => {
    console.error(`FAIL - ${message}`);
    failures++;
};

const launchOptions = { args: ['--no-sandbox'] };
if (process.env.CHROMIUM_PATH) launchOptions.executablePath = process.env.CHROMIUM_PATH;
const browser = await chromium.launch(launchOptions);

async function join(name) {
    const page = await browser.newPage({ viewport: { width: 900, height: 620 } });
    page.on('pageerror', (error) => fail(`${name}: uncaught ${error.message}`));
    await page.goto(URL);
    await page.fill('#name', name);
    await page.click('#join-form button');
    await page.waitForFunction(() => state.selfId !== null, null, { timeout: 10_000 });
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

const snapshot = (page) => page.evaluate(() => ({
    version: state.version,
    selfId: state.selfId,
    mapId: state.map && state.map.id,
    actors: [...state.actors.values()].map((a) => ({ id: a.id, name: a.name, x: a.x, y: a.y })),
}));

try {
    // ---- two clients share one map ---------------------------------------
    const ala = await join('Ala');
    const bob = await join('Bob');
    await ala.waitForFunction(() => state.actors.size >= 2, null, { timeout: 10_000 });

    const alaView = await snapshot(ala);
    const bobView = await snapshot(bob);

    alaView.mapId ? ok('init carried the map definition') : fail('init had no map');
    alaView.actors.length === 2 ? ok('Ala sees both characters') : fail(`Ala sees ${alaView.actors.length}`);
    bobView.actors.length === 2 ? ok('Bob sees both characters') : fail(`Bob sees ${bobView.actors.length}`);
    alaView.selfId !== bobView.selfId ? ok('each client owns a distinct actor') : fail('shared actor id');

    // ---- movement is server-driven and reaches the other client ----------
    const start = bobView.actors.find((a) => a.id === alaView.selfId);
    await ala.mouse.click(200, 200);
    await bob.waitForTimeout(STEP_MS * 8);

    const moved = (await snapshot(bob)).actors.find((a) => a.id === alaView.selfId);
    moved.x !== start.x || moved.y !== start.y
        ? ok(`Bob saw Ala walk ${start.x},${start.y} -> ${moved.x},${moved.y}`)
        : fail('movement did not propagate to the other client');

    // ---- the server, not the client, refuses a wall ----------------------
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
    const step = await pickFreeDirection(ala);
    if (!step) {
        fail('no free tile next to the character; cannot test keyboard movement');
    } else {
        await ala.keyboard.press(step.key);
        await ala.waitForTimeout(STEP_MS * 3);
        const walked = (await snapshot(ala)).actors.find((a) => a.id === alaView.selfId);

        walked.x === step.fromX + step.dx && walked.y === step.fromY + step.dy
            ? ok(`tapping "${step.key}" walked ${step.fromX},${step.fromY} -> ${walked.x},${walked.y}`)
            : fail(`"${step.key}" from ${step.fromX},${step.fromY} left the character at ${walked.x},${walked.y}`);
    }

    // ---- typing must not walk the character ------------------------------
    // The easiest thing in the whole feature to get wrong: chatting "wadas"
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

    const again = await pickFreeDirection(ala);
    if (!again) {
        fail('no free tile next to the character; cannot re-test movement');
    } else {
        await ala.keyboard.press(again.key);
        await ala.waitForTimeout(STEP_MS * 3);
        const walked = (await snapshot(ala)).actors.find((a) => a.id === alaView.selfId);

        walked.x === again.fromX + again.dx && walked.y === again.fromY + again.dy
            ? ok('movement works again once the chat box is left')
            : fail(`character stayed at ${walked.x},${walked.y} after Escape`);
    }

    // ---- a dropped socket resumes the same character ---------------------
    const before = alaView.selfId;
    await ala.evaluate(() => state.ws.close());
    await ala.waitForTimeout(2_500);
    const resumed = await snapshot(ala);

    resumed.selfId === before
        ? ok(`reconnect resumed actor ${before} rather than spawning a new one`)
        : fail(`reconnect produced actor ${resumed.selfId}, expected ${before}`);
} finally {
    await browser.close();
}

console.log(failures ? `\n${failures} check(s) failed` : '\nall checks passed');
process.exit(failures ? 1 : 0);
