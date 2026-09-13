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
    return { x: self.x, y: self.y, name: self.name };
});

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
    await page.click('#register-form button[type="submit"]');
    await enterWorld();

    // Walk a few tiles away from the spawn so "it worked" cannot be confused
    // with "everyone starts here anyway".
    for (const key of ['a', 'a', 'w', 'w', 'w']) {
        await page.keyboard.press(key);
        await page.waitForTimeout(STEP_MS + 120);
    }

    const at = await here();
    writeFileSync(file, JSON.stringify({ ...account, character, ...at }));
    console.log(`recorded ${at.name} at ${at.x},${at.y}`);
} else {
    // Logging in, not registering: the account has to have survived too.
    await page.fill('#login-name', account.login);
    await page.fill('#login-password', PASSWORD);
    await page.click('#login-form button[type="submit"]');
    await enterWorld();

    const at = await here();
    if (at.x === account.x && at.y === account.y) {
        console.log(`ok   - ${at.name} logged back in at ${at.x},${at.y} after a server restart`);
    } else {
        console.error(`FAIL - expected ${account.x},${account.y} but found ${at.x},${at.y}`);
        exitCode = 1;
    }
}

// Closing the page drops the socket, which is what tells the server to save.
await page.close();
await browser.close();
process.exit(exitCode);
