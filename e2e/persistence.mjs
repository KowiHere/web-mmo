/**
 * Half of the restart check. Run by restart-check.sh, which stops and starts
 * the server between the two halves.
 *
 *   node persistence.mjs record <file>   register, walk somewhere, write it down
 *   node persistence.mjs verify <file>   log back in, expect to be standing there
 *
 * Splitting it this way is the point: nothing in one process proves a character
 * outlived the process. Only a real stop and start does. The second half logs
 * in rather than registering, so it also proves the account survived.
 *
 * It also carries an item, for the same reason. A reconnect inside the grace
 * period finds the character still standing in memory, so an item that "came
 * back" there never went near the database - only a restart can tell.
 */
import { chromium } from 'playwright';
import { readFileSync, writeFileSync } from 'node:fs';

const [mode, file] = process.argv.slice(2);
const URL = process.env.GAME_URL || 'http://localhost:8080/';
const PASSWORD = 'haslo-do-testow';
const STEP_MS = 300;

const launchOptions = { args: ['--no-sandbox'] };
if (process.env.CHROMIUM_PATH) launchOptions.executablePath = process.env.CHROMIUM_PATH;

const browser = await chromium.launch(launchOptions);
const page = await browser.newPage({ viewport: { width: 900, height: 620 } });
await page.goto(URL);

let exitCode = 0;
const account = mode === 'record'
    ? { login: `trwalka-${Math.random().toString(36).slice(2, 6)}` }
    : JSON.parse(readFileSync(file, 'utf8'));
const character = account.character || `Trwalka-${account.login.split('-')[1]}`;

const here = () => page.evaluate(() => {
    const self = state.actors.get(state.selfId);
    const worn = state.bag ? (state.bag.worn || []).map((item) => item.defId) : [];
    return { x: self.x, y: self.y, name: self.name, worn, classId: state.you.classId };
});

/** Kills whatever is nearest until something is in the bag, then wears it. */
async function findAndWearSomething() {
    for (let attempt = 0; attempt < 8; attempt++) {
        const busy = await page.evaluate(() => {
            const self = state.actors.get(state.selfId);
            return !!(self && self.inFight);
        });
        if (!busy) {
            const prey = await page.evaluate(() => {
                const self = state.actors.get(state.selfId);
                const mobs = [...state.actors.values()].filter((a) => a.kind === 'MOB');
                mobs.sort((a, b) => Math.abs(a.x - self.x) + Math.abs(a.y - self.y)
                    - Math.abs(b.x - self.x) - Math.abs(b.y - self.y));
                return mobs[0] ? mobs[0].id : null;
            });
            if (prey !== null) {
                await page.evaluate((id) =>
                    state.ws.send(JSON.stringify({ type: 'attack', targetId: id })), prey);
            }
        }
        await page.waitForTimeout(2_000);

        const wearable = await page.evaluate(() =>
            (state.bag ? state.bag.carried : []).find((item) => item.wearable) || null);
        if (wearable) {
            await page.evaluate((id) =>
                state.ws.send(JSON.stringify({ type: 'equip', itemId: id })), wearable.id);
            await page.waitForFunction((id) => (state.bag.worn || []).some((i) => i.id === id),
                wearable.id, { timeout: 10_000 });
            return wearable.defId;
        }
    }
    return null;
}

async function enterWorld() {
    await page.waitForSelector(`.character:has-text("${character}")`, { timeout: 10_000 });
    await page.click(`.character:has-text("${character}")`);
    await page.waitForFunction(() => state.selfId !== null, null, { timeout: 10_000 });
}

if (mode === 'record') {
    await page.click('.tab[data-tab="register"]');
    await page.fill('#register-login', account.login);
    await page.fill('#register-password', PASSWORD);
    await page.fill('#register-character', character);
    // A mage rather than the default, so that "the class survived" cannot be
    // confused with "everybody gets the fallback anyway".
    await page.waitForSelector('#register-classes input[value="mag"]', { timeout: 10_000 });
    await page.check('#register-classes input[value="mag"]');
    await page.click('#register-form button[type="submit"]');
    await enterWorld();

    // Walk a few tiles away from the spawn so "it worked" cannot be confused
    // with "everyone starts here anyway".
    for (const key of ['a', 'a', 'w', 'w', 'w']) {
        await page.keyboard.press(key);
        await page.waitForTimeout(STEP_MS + 120);
    }

    const worn = await findAndWearSomething();
    if (!worn) {
        console.error('FAIL - could not find anything to wear; nothing to prove about items');
        process.exit(1);
    }

    const at = await here();
    writeFileSync(file, JSON.stringify({ ...account, character, ...at }));
    console.log(`recorded ${at.name} the ${at.classId} at ${at.x},${at.y}`
        + ` wearing ${at.worn.join(', ')}`);
} else {
    // Logging in, not registering: the account has to have survived too.
    await page.fill('#login-name', account.login);
    await page.fill('#login-password', PASSWORD);
    await page.click('#login-form button[type="submit"]');
    await enterWorld();

    // Wait for the bag to arrive; it is a separate frame from the world.
    await page.waitForFunction(() => state.bag !== null, null, { timeout: 10_000 }).catch(() => {});
    const at = await here();

    if (at.x === account.x && at.y === account.y) {
        console.log(`ok   - ${at.name} logged back in at ${at.x},${at.y} after a server restart`);
    } else {
        console.error(`FAIL - expected ${account.x},${account.y} but found ${at.x},${at.y}`);
        exitCode = 1;
    }

    if (at.classId === account.classId) {
        console.log(`ok   - and still a ${at.classId}`);
    } else {
        console.error(`FAIL - was a ${account.classId}, came back a ${at.classId}`);
        exitCode = 1;
    }

    const missing = (account.worn || []).filter((defId) => !at.worn.includes(defId));
    if (!missing.length) {
        console.log(`ok   - and still wearing ${at.worn.join(', ')}`);
    } else {
        console.error(`FAIL - lost across the restart: ${missing.join(', ')}`);
        exitCode = 1;
    }
}

// Closing the page drops the socket, which is what tells the server to save.
await page.close();
await browser.close();
process.exit(exitCode);
