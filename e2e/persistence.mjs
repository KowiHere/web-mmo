/**
 * Half of the restart check. Run by restart-check.sh, which stops and starts
 * the server between the two halves.
 *
 *   node persistence.mjs record <file>   walk somewhere, write the tile down
 *   node persistence.mjs verify <file>   come back, expect to be standing there
 *
 * Splitting it this way is the point: nothing in one process proves a character
 * outlived the process. Only a real stop and start does.
 */
import { chromium } from 'playwright';
import { readFileSync, writeFileSync } from 'node:fs';

const [mode, file] = process.argv.slice(2);
const URL = process.env.GAME_URL || 'http://localhost:8080/';
const NAME = 'Trwalka';
const STEP_MS = 300;

const launchOptions = { args: ['--no-sandbox'] };
if (process.env.CHROMIUM_PATH) launchOptions.executablePath = process.env.CHROMIUM_PATH;

const browser = await chromium.launch(launchOptions);
const page = await browser.newPage({ viewport: { width: 900, height: 620 } });
await page.goto(URL);
await page.fill('#name', NAME);
await page.click('#join-form button');
await page.waitForFunction(() => state.selfId !== null, null, { timeout: 10_000 });

const here = () => page.evaluate(() => {
    const self = state.actors.get(state.selfId);
    return { x: self.x, y: self.y, name: self.name };
});

let exitCode = 0;

if (mode === 'record') {
    // Walk a few tiles away from the spawn so "it worked" cannot be confused
    // with "everyone starts here anyway".
    for (const key of ['a', 'a', 'w', 'w', 'w']) {
        await page.keyboard.press(key);
        await page.waitForTimeout(STEP_MS + 120);
    }
    const at = await here();
    if (at.x === undefined) {
        console.error('FAIL - could not read a position to record');
        exitCode = 1;
    } else {
        writeFileSync(file, JSON.stringify(at));
        console.log(`recorded ${at.name} at ${at.x},${at.y}`);
    }
} else {
    const expected = JSON.parse(readFileSync(file, 'utf8'));
    const at = await here();
    if (at.x === expected.x && at.y === expected.y) {
        console.log(`ok   - ${at.name} came back at ${at.x},${at.y} after a server restart`);
    } else {
        console.error(`FAIL - expected ${expected.x},${expected.y} but found ${at.x},${at.y}`);
        exitCode = 1;
    }
}

// Closing the page drops the socket, which is what tells the server to save.
await page.close();
await browser.close();
process.exit(exitCode);
