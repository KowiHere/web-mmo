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
    const ranks = (state.skills ? state.skills.skills || [] : [])
        .filter((skill) => skill.rank > 0)
        .map((skill) => `${skill.id}:${skill.rank}`);
    return {
        x: self.x, y: self.y, name: self.name, worn, ranks,
        classId: state.you.classId,
        hp: state.you.hp,
        maxHp: state.you.maxHp,
        purse: (state.purse ? state.purse.coins || [] : [])
            .filter((c) => c.amount > 0)
            .map((c) => `${c.id}:${c.amount}`),
        // Recorded so the second half can check it is *not* carried across.
        energy: state.you.energy,
    };
});

/** Picks a fight and runs from it, so the character is carrying a wound. */
async function getHurt() {
    const until = Date.now() + 90_000;
    while (Date.now() < until) {
        const state0 = await page.evaluate(() => ({
            hp: state.you.hp,
            maxHp: state.you.maxHp,
            inFight: !!(state.actors.get(state.selfId) || {}).inFight,
        }));
        if (state0.hp < state0.maxHp) {
            // Out of the fight, or dying would mend nothing and cost the wound
            // the point of being recorded.
            while (await page.evaluate(() => !!(state.actors.get(state.selfId) || {}).inFight)) {
                await page.evaluate(() => state.ws.send(JSON.stringify({ type: 'flee' })));
                await page.waitForTimeout(1_200);
            }
            return true;
        }
        if (!state0.inFight) {
            const prey = await page.evaluate(() =>
                [...state.actors.values()].find((a) => a.kind === 'MOB') || null);
            if (!prey) return false;
            await page.evaluate((id) =>
                state.ws.send(JSON.stringify({ type: 'attack', targetId: id })), prey.id);
        }
        await page.waitForTimeout(1_200);
    }
    return false;
}

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

/** Kills until there is something loose in the bag. Leaves it there. */
async function findSomethingLoose() {
    for (let attempt = 0; attempt < 8; attempt++) {
        const loose = await page.evaluate(() => (state.bag.carried || [])[0] || null);
        if (loose) return loose;
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
            if (prey !== null) {
                await page.evaluate((id) =>
                    state.ws.send(JSON.stringify({ type: 'attack', targetId: id })), prey);
            }
        }
        await page.waitForTimeout(2_000);
    }
    return null;
}

/**
 * Walks up to the storekeeper and gets the chest open.
 *
 * <p>There is no other way to see one: a chest is only sent to somebody
 * standing in front of it, which is the same door the stall has.
 */
async function openChest() {
    const until = Date.now() + 90_000;
    while (Date.now() < until) {
        const keeper = await page.evaluate(() =>
            [...state.actors.values()].find((a) => a.kind === 'NPC' && a.name.includes('Otton'))
            || null);
        if (!keeper) return false;
        while (await page.evaluate(() => !!(state.actors.get(state.selfId) || {}).inFight)) {
            await page.evaluate(() => state.ws.send(JSON.stringify({ type: 'flee' })));
            await page.waitForTimeout(1_200);
        }
        await page.evaluate((k) => {
            const spot = besideThem(k);
            state.walkingUpTo = k.id;
            requestMove(spot.x, spot.y);
        }, keeper);
        const greeted = await page
            .waitForFunction(() => [...document.querySelectorAll('#dialogue-options button')]
                .some((b) => /Otwieraj/.test(b.textContent)), null, { timeout: 15_000 })
            .then(() => true).catch(() => false);
        if (!greeted) continue;
        await page.click('#dialogue-options button:text-is("Otwieraj skład.")');
        const open = await page
            .waitForFunction(() => !!state.storage, null, { timeout: 10_000 })
            .then(() => true).catch(() => false);
        if (open) return true;
    }
    return false;
}

const inTheChest = () => page.evaluate(() => {
    const chest = (state.storage.chests || []).find((c) => c.scope === 'character');
    return (chest ? chest.items || [] : []).map((k) => k.item.id);
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

    // A rank in something, so "the skill survived" is a real claim rather than
    // one about a character that never learned anything.
    await page.evaluate(() => state.ws.send(JSON.stringify({ type: 'learn', skillId: 'regeneracja' })));
    await page.waitForFunction(
        () => (state.skills.skills || []).some((s) => s.id === 'regeneracja' && s.rank > 0),
        null, { timeout: 10_000 });

    const worn = await findAndWearSomething();
    if (!worn) {
        console.error('FAIL - could not find anything to wear; nothing to prove about items');
        process.exit(1);
    }

    // Hurt, and left hurt. Nothing in this game mends on its own, so a wound
    // is now a fact about a character that has to survive the process - and a
    // character recorded at full health would prove nothing, since that is
    // what a freshly created one would come back as anyway.
    // Getting hurt also means killing things, which is where money comes from -
    // so the purse below is filled by the same fight that leaves the wound.
    const hurt = await getHurt();
    if (!hurt) {
        console.error('FAIL - nothing ever landed a blow; there is no wound to keep');
        process.exit(1);
    }

    // Something put away, which is the only claim a chest can make: an item
    // that came back after a reconnect never went near the database, and one
    // that came back out of the chest after a restart can have done nothing
    // else.
    if (!(await findSomethingLoose())) {
        console.error('FAIL - nothing dropped that could be put away');
        process.exit(1);
    }
    if (!(await openChest())) {
        console.error('FAIL - the storekeeper never opened the chest');
        process.exit(1);
    }
    // Something loose, never what is being worn: this file also claims that
    // what a character had on survives the restart, and taking it off to stash
    // it would quietly turn that check into a claim about an empty back.
    const toStash = await page.evaluate(() => (state.bag.carried || [])[0] || null);
    let stashed = null;
    if (toStash) {
        await page.evaluate((id) => state.ws.send(JSON.stringify({
            type: 'deposit', itemId: id, tab: 0, account: false,
        })), toStash.id);
        const went = await page
            .waitForFunction((id) => (state.storage.chests || [])
                .some((c) => c.scope === 'character' && (c.items || [])
                    .some((k) => k.item.id === id)), toStash.id, { timeout: 10_000 })
            .then(() => true).catch(() => false);
        if (!went) {
            console.error('FAIL - nothing would go into the chest');
            process.exit(1);
        }
        stashed = toStash.id;
    } else {
        console.error('FAIL - nothing in the bag to put away');
        process.exit(1);
    }
    await page.evaluate(() => state.ws.send(JSON.stringify({ type: 'endTalk' })));
    await page.waitForTimeout(600);

    const at = await here();
    writeFileSync(file, JSON.stringify({ ...account, character, ...at, stashed }));
    console.log(`recorded ${at.name} the ${at.classId} at ${at.x},${at.y}`
        + ` wearing ${at.worn.join(', ')} and knowing ${at.ranks.join(', ')}`);
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

    const forgotten = (account.ranks || []).filter((rank) => !at.ranks.includes(rank));
    if (!forgotten.length) {
        console.log(`ok   - and still knowing ${at.ranks.join(', ')}`);
    } else {
        console.error(`FAIL - forgotten across the restart: ${forgotten.join(', ')}`);
        exitCode = 1;
    }

    const spent = (account.purse || []).filter((coin) => !at.purse.includes(coin));
    if (!(account.purse || []).length) {
        console.error('FAIL - nothing was ever earned, so there is no money to keep');
        exitCode = 1;
    } else if (!spent.length) {
        console.log(`ok   - and still holding ${at.purse.join(', ')}`);
    } else {
        console.error(`FAIL - lost across the restart: ${spent.join(', ')}`);
        exitCode = 1;
    }

    if (at.hp === account.hp) {
        console.log(`ok   - and still carrying the same wound (${at.hp}/${at.maxHp})`);
    } else {
        console.error(`FAIL - came back on ${at.hp} health, was left on ${account.hp}`);
        exitCode = 1;
    }

    if (!(await openChest())) {
        console.error('FAIL - the storekeeper never opened the chest after the restart');
        exitCode = 1;
    } else if ((await inTheChest()).includes(account.stashed)) {
        console.log('ok   - and what was put in the chest is still lying in it');
    } else {
        console.error(`FAIL - ${account.stashed} is not in the chest any more`);
        exitCode = 1;
    }

    // The other half of the claim: energy is deliberately *not* stored, because
    // it belongs to a fight. Coming back charged would mean it had been.
    if (at.energy === 0) {
        console.log('ok   - and carrying no energy out of a fight it is no longer in');
    } else {
        console.error(`FAIL - came back holding ${at.energy} energy`);
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
