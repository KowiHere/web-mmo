# web-mmo

Server-authoritative browser MMO, built along the lines that make this genre of
game work: the server simulates the world, the client draws it and asks for
things. Written in Java 21 / Spring Boot on the server and plain canvas on the
client, with no build step in front of the browser.

## Where the truth lives

Every rule lives on the server, and the client is never asked to enforce one.
It sends *"I want to stand on tile (6, 6)"*; the server decides whether that is
reachable, finds the path, and moves the character one tile at a time. Clicking
a wall does nothing because the server refuses it, not because the client
declined to ask.

```
browser ──► WebSocket ──► command queue ──► map thread ──► delta ──► every client
  canvas                   (lock-free)      (single writer)
```

### One map, one thread, one writer

Each map runs on its own thread at a fixed 10 Hz tick. Commands arrive through a
lock-free queue and that thread is their only consumer — which is also the only
thread that ever touches actors, positions or paths. There is no lock on world
state anywhere in this repository, because nothing else can reach it.

**Two maps are where that claim is tested.** A character walking through a door
is the only moment two map threads have anything to do with each other, and
what crosses between them is an immutable `SavedCharacter` and a socket —
never a live actor, which is exactly the thing one thread must not read while
another writes it. The map giving the character up removes its own copy first;
the map receiving it takes the ordinary `Command.Join` path, so there is one way
into a map and not two. The world keeps a signpost of which map each socket is
on, and that concurrent map is the entire synchronisation between them.

Spring's role stops at the door: dependency injection, configuration and the
WebSocket transport. Below `WorldService` there is no Spring, no JPA and no
framework of any kind — just a loop.

### Deltas, not snapshots

The server never resends the world. After `init`, every frame is a delta
carrying only what changed, stamped with a version number that goes up by one:

```json
{"type":"delta","v":8,"moved":[{"id":3,"fx":14,"fy":17,"x":13,"y":17,"dir":"LEFT","ms":300}]}
```

An idle tick sends nothing at all. A client that notices a gap in the version
sequence knows its picture is stale and reloads, rather than drifting quietly
out of sync.

### Characters outlive the process

An embedded H2 database file under `data/` keeps where each character was last
standing. Nothing to install and no container to run — the file appears on first
start and Flyway owns the schema, so a migration is a file you can read rather
than a side effect of some entity class.

Saving happens **behind** the tick, never inside it. The map thread drops an
immutable snapshot into a queue and carries on; one writer thread drains it into
the database. A slow write delays a save, not the world. Positions are written
when a player leaves, every 15 seconds while they are moving, and once more when
the server shuts down.

Only one server may run against one database file at a time — H2 holds a lock,
and a second instance fails to start. That is the right answer rather than an
inconvenience: two worlds writing the same characters would corrupt both.

### Who you are

An account, a password, and as many characters as you care to make. Register,
pick a character, walk around; the login you type is not the name anyone sees,
because a character's name is public and half a credential should not be.

**Identity is settled at the WebSocket handshake, not in a message.** The
session cookie and the character being entered are checked before the socket
exists, so there is no moment where a connection is open and anonymous, and
nothing a client can later claim about who it is. Two separate questions get
asked there, and conflating them is the classic way one player ends up walking
around as another:

- is this session real? — otherwise the handshake is refused
- does *this account* own *this character*? — otherwise it is refused too

Passwords are hashed with BCrypt. Session tokens are random, kept in an
`HttpOnly` cookie, and only their SHA-256 is stored — a copy of the database is
not a pile of working logins. A wrong password and a login that does not exist
produce the same message and take about the same time, so the login form cannot
be used to find out which accounts are real.

Two things worth knowing before this leaves your machine:

- **Over plain HTTP the password crosses the network in the clear.** On
  localhost that is fine, and through Tailscale the tunnel is encrypted anyway.
  Exposed any other way, HTTPS stops being optional.
- `game.session.secure-cookie` is **off** by default and must stay off for
  `http://localhost`: a cookie marked `Secure` is silently dropped over plain
  HTTP, and the symptom is a login that appears to do nothing at all. Turn it on
  the moment the game is served over HTTPS.

### The map is not empty

Creatures live in the same loop and on the same thread as players — they are
actors like any other, so deltas, interpolation and rendering needed no changes
to carry them. They are defined as content in `src/main/resources/mobs/*.json`
and placed by the map that wants them.

**A tier is not a difficulty multiplier; it is a spawn policy.** That is the
distinction worth keeping, because without it a boss ends up being a fat wolf:

| tier | how it appears |
| --- | --- |
| `MOB` | stands at a marked point on a shared map |
| `ELITE` | turns up on its own schedule, somewhere unpredictable, often escorted |
| `HERO` | dungeon boss, placed by an instance — **not implemented** |
| `COLOSSUS` | raid boss, placed by an instance — **not implemented** |

The last two exist in the enum and the loader **refuses to start** on a
definition using one, rather than accepting a creature that would silently never
appear. They need instances and parties, which are a milestone of their own.

An elite appears well away from the spawn and its escort is only ever brought up
to strength, never doubled: escorts outlive the elite they arrived with, so
spawning a fresh pair each time would quietly carpet the map in wolves over an
afternoon.

Creatures are never saved. They are derived from map data, so a restart brings
them back by itself — and a creature has no account to own it, so writing one
would violate the very foreign key that keeps characters honest.

### Mobs and NPCs are different things

A **mob** is hostile: combat statistics, behaviour, a respawn timer, loot. It
dies. An **NPC** is something you interact with, and is its own kind of actor
rather than a mob with its aggression switched off — so `attack` refuses one for
the same reason it refuses a player, and nothing had to be added to make that
true.

What an NPC **is** and what it **does** are kept apart, and this is the whole
design:

| | means | values |
| --- | --- | --- |
| kind (`NpcKind`) | how it presents itself | `PERSON`, `OBJECT` |
| function (`NpcFunction`) | what it does — a list | `DIALOGUE`, `HEALER`, … |

Folding them together produces a `SHOPKEEPER` who cannot also give a quest, and
then a `SHOPKEEPER_WITH_QUEST`. A noticeboard talks; a blacksmith sells, repairs
and talks; neither is a different kind of thing from the other because of it.

`DIALOGUE`, `HEALER`, `SHOP`, `MASTER` and `STORAGE` work today. Every other
function says so and **stops the server** when content names it, exactly as an
unspawnable mob tier does — `TELEPORT` because a person who moves you is a
different thing from a tile that leads outside, `QUEST` because there is nowhere
to keep a progress. Content that
names one would otherwise load an NPC who opens, offers nothing, and looks to
every player like a bug in the client.

A conversation is a tree of nodes, each with the options out of it, and the
loader refuses a `goto` to a node that does not exist, a node with no way out,
and a node that cannot be reached from the start. Where a player has got to is
held **on the server**, and the client answers by the index of the option it was
offered — never by naming a node, which would let it jump straight to whatever
is at the bottom of the tree.

An option says where the conversation goes — to another node, or to the end of
it — and may carry a **deed** besides. "Patch me up" both heals and gets an
answer; splitting that into two clicks would be an interface chore pretending to
be a rule. `END` is the one action that is itself somewhere to go, so it is the
only one allowed to have no `goto` beside it.

The NPCs the game ships with are the design being tested rather than a shortage
of imagination. A herbalist **talks and heals** — a function is a list, so one
person does two things without ever becoming a kind of thing that does both. A
trapper's chest **talks and trades** — kind and function are independent, so a
*thing* can keep a shop. Every deed is checked against the function list in both
directions, and that check is one entry in a map per function rather than any
new logic: a healer nobody can ask to heal, and a stall opened by somebody who
is not listed as a shopkeeper, are each refused at startup.

### Money is plural

There is no column called `gold`, and there never will be. Currencies are a
registry in `resources/currencies/`, and what a character holds is rows in
`character_currency` — one per kind of money. The starter map ships two: gold,
which boars and wolves carry, and wolf fangs, which only wolves drop and only
the trapper's chest accepts. **A purse full of gold buys nothing from the
chest**, and that is the single test this whole shape exists for.

A price is a property of an item, not of a shop: a trader says which goods and
which currency, and `value` on the item says the rest. Buying costs `value`;
selling returns a fraction of it. That fraction is the only place money leaves
this world — at the full price, buying and selling the same thing is a free loop
and loot is worth nothing.

A trader buys back **what they sell and nothing else**, which is why the chest
does not pay fangs for swords. A stall opens from a dialogue option, so it
inherits every door the conversation has — the range, the refusal in a fight,
and the ending the moment you walk away. There was a second range check on
buying; it never once fired, and a guard that cannot run is worse than no guard,
because it reads like the rule lives in two places.

### Masters keep a class; they do not sell one

A player spends their own skill points from their own panel, wherever they are
standing — there is no NPC who teaches. What a **master** offers is that same
point for **half the price**, and the only way to take points back.

A point costs a flat amount per rank, rising with the **level** rather than with
the rank. Income rises with the level too, so the level is what makes the price
weigh anything; a price that rose with the rank would mean spreading points
thinly is a way of paying less for the same number of them. **The first rank of
anything is free**, so a new character with one point and no money can see the
system work before it has earned a coin.

A reset gives every spent point back and costs **exactly what putting them back
here would cost** — so it introduces no number of its own, and the free first
ranks are free to undo, because nothing was paid for them.

Each master keeps **one class and serves nobody else**. That restriction is the
only thing that makes three masters three NPCs rather than one wearing three
names: a warrior at the mage's master is a warrior having a chat, at full price.

The price rides in the `you` frame rather than in `skills`, because it changes
the moment you walk up to somebody while `skills` goes out a few times an hour —
the same mistake `affordable` was, and avoided the same way. Walking up to your
master and walking away both send a fresh `you`, so the panel's price halves and
unhalves with nothing asked for.

**One thing to say plainly**: with a single map, nobody will ever pay the panel
price, because the master is ten seconds away. The discount starts to weigh
something when the world is bigger and the master is two maps from where you
levelled. Today its real job is to give the masters a reason to stand there.

### Doors, and where you wake

A **door** is a tile. Step on it and you are somewhere else; there is nothing to
click and nothing to confirm, because a door that needed a second action is a
door people walk onto and wonder about.

Doors are content, and the loader checks them **against every other map at
once** — a door names a map, a tile and sometimes a key, and each of those is
only right or wrong in the company of the others. It refuses one that leads to a
map that is not there, one that lands in a wall, one that sits on the tile
everybody arrives at, and **one that lands on another door**: walking in would
take the same step twice, through and straight back, and whoever tried it would
be thrown between two maps until they closed the tab. That last check caught the
first pair of doors this game ever had, written by me, half an hour after I
wrote the check.

A door may ask for a minimum level, a maximum level, and something in the bag.
There are two kinds of something, and the difference is the whole of it:

* `requiresItem` is a **key**. It is not taken, because a door that eats what
  opened it can be walked through exactly once.
* `consumesItem` is a **ticket**. It is taken, and that is the one passage in
  the game that **asks first**.

The question is not a conversation and never becomes one: one line saying where
it leads and what it costs, and a yes. The server does not remember having
asked. A yes carries the tile it is about and is checked from scratch — standing
there, still that door, still within the thresholds, ticket still in the bag —
so a client that answers a question nobody asked has simply taken the step and
agreed to it in one go, through exactly the same checks. Two yeses in the same
tick pay once, because the handover happens at the end of a tick and until then
the character is still standing on the door with the ticket in hand.

The glade's trader sells a torch, and the wood has a cave mouth that burns one
to let you in — the first shipped door to use a threshold at all: it opens from
level 6. A torch is the first item in the game with **no slot**: carried, never
worn, granting nothing while it sits in the bag. The item loader refuses a worn
item that grants nothing *and* a carried one that grants something, because
either way the file says one thing and means another.

Where you **wake up** after dying is a property of the map you died on, written
in its content rather than worked out from a graph of maps: "the nearest town"
is a judgement about the world, and deriving it from distances would mean the
answer changing because somebody moved a door. The wood sends its dead back to
the glade, which is where the healer is — and since nothing regenerates, a map
that woke its own dead would be a map you wake up on with one point of health
and no way to mend.

Dying far from home uses the same handover a door does, with the character still
unconscious when it lands.

### Fighting

A fight is an object owned by a map and settled in that map's tick — not a
thread, not a queue of its own. You send `attack <id>` and the **server walks
your character over** and starts the fight when it arrives; requiring you to
line yourself up first would be an interface chore pretending to be a rule.

Rounds resolve every 1.5 seconds. Everyone still standing strikes once, in a
fixed order by actor id so the same line-up always resolves the same way. The
client predicts nothing and is handed the result — which is the whole reason a
turn-based fight suits this engine: **it is indifferent to latency**.

```
reduction = armour / (armour + 20)
damage    = max(1, round(attack * random(0.85..1.15) * (1 - reduction)))
```

Armour reduces by a fraction rather than subtracting, because subtraction leaves
only two outcomes — armour that does nothing and armour that stops everything —
with nothing in between to tune. The floor of 1 guarantees every fight ends.

You are walked to whatever you told the server to attack, and **kept walking if
it wanders off** — otherwise ordering an attack on something that moves lands
you on the tile it used to occupy, which from your side looks like the order
quietly not working.

**Movement is refused while you are fighting.** `flee` is settled at the start of
the next round: succeed and the fight is over, fail and you lose your own blow
while your opponent strikes normally.

A level heals you to full and pays three attribute points; where a character's
numbers actually come from is **What a character is made of**, below.

**Experience is private.** Deltas go to everyone on the map, so progress travels
in a separate `you` frame addressed to one client — sent *after* the delta it
belongs to, so a character is never told it is dead while still being drawn
where it fell.

Kill a creature and it leaves the world, paying experience and whatever its loot
table rolls; its spawn point counts down `respawnSeconds` and puts it back. An elite has no spawn point, so it dies for
good and the next one arrives on the ordinary roll.

Die and you are put back at the spawn on **one point of health** and left
**lying there**: for a while the character cannot be played at all. How long
rises with the level — twenty seconds a level, up to five minutes — because a
death should cost more the further along you are, and because the other end of
that rule turns one mistake into a quarter of an hour looking at a screen.

The moment you may play again is stored as a timestamp, so closing the tab
shortens nothing and a restart forgets nothing. You can still log in; there is
simply nothing you can do. **Every command is refused except chat** — the
character is unconscious, the person at the keyboard is not, and three minutes
with no way to say "back shortly" punishes the wrong one of the two. Nothing
will attack a body that cannot fight back, run or refuse.

This replaced a minute of halved attack and armour, which had only ever been a
stand-in for it. One event is paid for once.

That single point is the rule the rest of the game hangs off: **nothing
regenerates.** Health comes back from a healer, and from nothing else. Waking up
mended, which is what used to happen, made dying the only cure in the game — so
the best thing a hurt character could do was find a wolf and lose to it.

Two consequences worth stating plainly, because both look like bugs the first
time:

- **Closing the tab does not end a fight.** Your character stands in the world
  for the disconnect grace period, and a fight it is in goes on without you — so
  you can come back dead. The alternative is worse: the most effective combat
  tactic in the game would be closing the browser.
- **Nothing will attack you within four tiles of the spawn.** Every character
  appears there and returns there after dying, on one point of health and
  unable to act for a while; without a truce on that ground one death becomes a
  loop a new character cannot break — and lying there unconscious, it could not
  even run from it. A healer has to stand inside that ring for the same
  reason, and a test on the shipped content refuses one that does not.

### What a character is made of

Statistics used to come from the level. The rule behind that was never "the
level decides" — it was **store nothing that can be computed**, so that nothing
can drift out of agreement with anything else. That rule is untouched. Only the
chain got longer:

```
level  →  points to spend  →  attributes + equipment  →  combat statistics
```

Three attributes. What a character starts with comes from its **class**, below;
every level pays **three points for the player to spend**, on whatever they like.

```
maxHp   = 40 + 5 * strength        dodge       = min(35%, 1% * agility)
attack  =  2 + the attribute       second blow = min(40%, 1.2% * agility)
          your class fights with
armour  =  1 + equipment
```

The constants are chosen so that five of each — what a character had before
classes decided otherwise — is still exactly 65 health and 7 attack. That is not
nostalgia: the creatures on the starter map were balanced against those numbers,
and moving them would quietly have made the map unwinnable again, for new
players only. A test now checks that **each** class can beat the starter
creatures and that none can beat an elite, because three classes fighting with
three attributes means a change that suits one can ruin another.

**Agility does not buy attack speed**, whatever the word suggests. A round is
still a round and everybody still gets one turn in it; what agility buys is the
part of speed that fits a turn — sometimes the turn is two blows. Both of its
payoffs are capped, because a character that eventually cannot be hit is a
character in a fight that never ends.

**Intellect is the mage's attribute and nobody else's.** Strength is health for
everyone and agility is evasion for everyone, but intellect only does anything
for the class whose blows are made of it. That is a real asymmetry rather than
an oversight, and it is the next thing classes will have to answer for.

It used to buy mana. Mana is gone: see **Energy**, below.

Only attributes, points and what is worn are stored. Not one derived number is.

### Classes

A class is not a bundle of bonuses attached to an otherwise identical
character. It decides two things that show up in every single fight:

| | fights with | armour it ignores | health |
| --- | --- | --- | --- |
| Wojownik | siła | none | the most |
| Łowca | zwinność | a quarter | ordinary |
| Mag | inteligencja | most of it | the least |

**Which attribute a character's blows are made of** is the important one.
Without it every class would want strength and the other two attributes would
be a tax — which is exactly what they were before classes existed. With it, a
mage's intellect is its weapon, and that is what intellect was always meant to
be.

**How much armour those blows pass through** is what keeps the frailest class
worth playing. A mage dies faster than anything else on the map and hits an
armoured target for far more than a warrior does.

Every class uses the same curve for turning its attribute into attack. If a
mage got more out of a point of intellect than a warrior gets out of a point of
strength, the classes would differ in how fast they grow rather than in how they
fight, and one of them would simply be the right answer.

**Points stay yours to spend.** A class says where a character starts and how it
fights, not what it is allowed to become — so a strong mage is possible, and
being possible is part of the point.

The class is chosen when the character is created and stored beside it. A stored
class the content no longer has falls back to the warrior rather than refusing
to let anyone in: losing a character to a renamed file would be far worse than
swinging a sword for an afternoon until somebody notices.

A mage is not a mage because of a resource — it is a mage because its blows are
made of intellect and go through armour. What it spends is the same energy
everybody spends; see below.

### Energy and skills

Energy belongs to a **fight**, not to a character:

```
ceiling    100, the same for everybody
at the start of a fight   0
each round +10, and +5 more for every rank of Regeneracja
when the fight ends       0 again
```

This replaced mana, and the difference is the entire point. Mana was a bar that
was always full, never moved and was spent by nothing — a decoration. Energy is
empty when you walk into a fight and fills while you are in one, so the question
it asks is a real one: swing now, or wait a round and throw something.

**Nothing about energy is stored.** It always starts at zero, so a stored value
could only ever read back as the one value it can have.

The ceiling is flat on purpose: the **only** thing that decides how fast you can
afford anything is how many points went into charging faster. One lever, in one
place. Were the pool set by an attribute as well, the same thing would be tuned
from two directions at once.

A consequence worth saying out loud, because it looks like a broken skill the
first time: **a boar dies before a mage can charge a spell.** Long fights suit
skills, short ones suit swinging. That is the design, not a bug.

Skills are content like everything else, in `src/main/resources/skills/*.json`,
with one shape of effect — a multiplier on your attack, a number of blows, and
optionally how much armour those blows pass through:

| | | |
| --- | --- | --- |
| Wojownik | Potężny cios | one blow at double damage |
| Łowca | Seria | three weaker ones, reusing agility's second-blow machinery |
| Mag | Błyskawica | one blow that armour barely stops |
| everyone | Regeneracja | passive: each rank charges you faster |

Three numbers rather than three kinds of effect, so a new idea is a new file
rather than new code. The loader refuses a skill belonging to a class that does
not exist, one that is paid for and strikes nothing, one nobody could ever
afford, and content with no `regeneracja` in it at all — that last one because
otherwise everybody would charge at the base rate for ever and no point spent on
it could do anything, which nothing else would report.

**Skill points are separate from attribute points**, and there is one per level
*including the first*, so a brand new character has a choice to make rather than
a system it cannot see yet. Attribute points stay at three per level after the
first. Two simple rules side by side read better than one rule with an exception.

Using a skill is a request for the coming **round**, settled where fleeing is
settled. It is paid for whether or not a single blow lands: energy returned by a
dodge would make throwing it every round the obvious play. Too little energy and
you are told so and swing normally, rather than losing the round to nothing.

Creatures have no energy and no skills. Giving them either means giving them
something to spend it on, which is a milestone of its own.

### Items

Content, like maps and creatures: `src/main/resources/items/*.json`, loaded and
validated at startup by a loader that is `MobDefLoader`'s twin. An unknown slot,
a duplicated id, an item whose bonuses are all misspelled, or a creature that
drops something which is not an item — each stops the server rather than
producing an item somebody picks up and cannot work out why it does nothing.

```json
{
  "id": "zardzewialy-miecz",
  "name": "Zardzewiały miecz",
  "slot": "WEAPON",
  "requiresLevel": 1,
  "bonuses": { "attack": 3 }
}
```

An item **is** its definition: two rusty swords are the same sword. Rolled
bonuses and rarities would make each copy its own thing, which is a milestone
with its own questions. Three slots — weapon, chest, trinket — which is enough
to prove that bonuses add up, that a slot holds one thing, and that a level
requirement is enforced. A fourth would prove none of it.

Everything is refused by the **server**: wearing something above your level,
changing clothes mid-fight, spending a point you have not earned. A client that
never draws the button is a courtesy, not a rule.

Loot goes straight into the killer's bag, which holds twenty things. A full bag
does not swallow a reward silently — nothing is dropped and you are told why.

**The shipped items are test content.** A boar drops a sword every single time,
which is why the browser checks can be decisive rather than patient. It is not a
balance proposal.

### The chest, and one character at a time

A bag holds twenty things and that is the hard ceiling: a full one ends a hunt,
because the next rare drop has to be sold on the spot. The storekeeper is the
second container — two chests, in tabs of twenty:

* **the character's own**, one tab free and five more for gold;
* **the account's**, shared by every character on it, one tab free and five more
  for a premium currency nothing grants yet.

Both keep money as well as things, and neither will take what is being worn —
the same rule as selling, for the same reason.

An item in a chest is the **same instance** as an item in a bag: one table, told
apart by the column that says which tab it is in. Two tables would mean moving a
row on every deposit and the same truth living in two homes. The account's chest
has a table of its own, because it outlives any one character.

That shared chest is the first state in this game that two map threads could
write at once — two characters of one player, standing on two maps. There is no
lock for them and there will not be one. Instead: **one character from an
account is in the world at a time**, settled at the door by `WorldService`
before anything joins. That rule is what stands in place of the lock, and it is
why the account's chest can be held in memory beside the character's own and
travel through a door with it.

### Disconnecting is not leaving

Drop your connection and your character stays standing in the world for 30
seconds. Reconnect inside that window with the token you were given and the
server hands you the same character back, replaying the deltas you missed
instead of reloading the map. Past the window the character is reaped and
everyone is told it left.

### The client only invents motion

The server speaks in whole tiles at 10 ticks per second. The client interpolates
between two server-confirmed tiles so that reads as smooth movement at 60 fps —
and that is the *only* thing it makes up. There is no client-side prediction and
no client-side pathfinding, so there is nothing for the two sides to disagree
about.

Other players are drawn 150 ms behind the newest known state. The server ticks
perfectly evenly but the network does not deliver evenly, so playing each step
the moment its frame lands turns smooth movement into stutter; a small buffer
gives late frames somewhere to land. Your own character is exempt — it already
waits for the server to agree, and delaying it on top of that would just feel
heavy.

## Running it

```bash
./mvnw spring-boot:run
```

Then open <http://localhost:8080> — and open it a second time in another tab to
see the multiplayer half actually working.

Register on first visit, then pick a character from the list.

| key | does |
| --- | --- |
| `WASD` / arrows | walk one tile, held to keep walking |
| click | walk to that tile — or, on a creature, go and fight it; on an NPC, go and talk |
| `i` | the character panel: attributes, equipment, bag, skills |
| skill buttons | bottom right during a fight, greyed out while the energy is short |
| `Enter` | chat; sending returns the keyboard to the game |
| `Esc` | leave the chat box |
| `F3` | frame rate, delta rate, world version, buffer depth |

`F3` deliberately shows no latency figure: an honest round-trip needs a
ping/pong the protocol does not have, and an invented number would be worse
than none.

```bash
./mvnw test
```

To check the multiplayer half for real, start the server and drive two browser
tabs at it:

```bash
cd e2e && npm install && npm test
```

That covers what unit tests cannot — two people seeing each other move, the
server refusing an illegal destination, a dropped socket resuming the same
character, a fight fought from the browser — one attack command, health bars
falling, a kill paying experience, and a death putting the character back at the
spawn and out of action — the loot from that kill being worn from the character panel,
and energy filling round by round until a skill can be thrown with it.

The combat checks are deliberately written as "something was wounded, something
died" rather than naming a particular creature. The map hunts back, so which
fight a character ends up in is not the script's to decide, and a check that
insists on one is testing the dice.

One check needs the server stopped and started around it, so it runs itself:

```bash
./mvnw package -DskipTests
e2e/restart-check.sh
```

It registers an account, walks the character somewhere, kills something and puts
on what it dropped, kills the server, starts it again, and then **logs in**
rather than registering — so it fails unless the account, the character, its
class, its skill ranks and the item all survived. It also checks the opposite
for energy: coming back holding any would mean it had been stored, and it is
not meant to be. Nothing inside a single process can prove that: a reconnect
within the grace period finds the character still standing in memory, so an item
that "came back" there never went near the database.

## Maps

Maps are content, not database rows: `src/main/resources/maps/*.json`, loaded at
startup, versioned with the code, editable in any text editor.

```json
{
  "id": "starter",
  "tileSize": 32,
  "spawn": [14, 17],
  "collision": ["##########", "#........#", "##########"]
}
```

`#` blocks, `.` is walkable. The loader refuses to start on a ragged row, a spawn
point outside the map, or a spawn point inside a wall — the three mistakes that
are easy to make by hand and annoying to diagnose in a running game.

## Wire protocol

Client to server:

| message  | fields         | meaning                                |
| -------- | -------------- | -------------------------------------- |
| `hello`  | name, token, since | join, or resume the character behind `token` |
| `move`   | x, y           | request a path to this tile            |
| `attack` | targetId       | go to that creature and fight it       |
| `flee`   | —              | try to leave the fight next round      |
| `equip`  | itemId         | put on something from the bag          |
| `unequip`| slot           | take off what is in that slot          |
| `spend`  | attribute      | spend one earned point                 |
| `use`    | skillId        | use a skill in the coming round        |
| `learn`  | skillId        | put one earned point into a skill, for a price |
| `buy`    | itemId         | buy that from the trader you are talking to |
| `sell`   | itemId         | sell that particular copy of it        |
| `talk`   | npcId          | start talking to somebody next to you  |
| `choose` | option         | answer, by the index you were offered  |
| `endTalk`| —              | close the conversation                 |
| `chat`   | text           | say something on this map              |

Server to client: `init` (the whole world once), `delta` (what changed), `you`
(your own character, its class, its energy and what a skill point costs where
it is standing, to your socket only), `bag` (what
it is wearing and carrying, likewise), `skills` (what it has learned, likewise),
`dialogue` (what somebody is saying to you, likewise — one with no text is the
conversation closing, which happens by walking away as often as by saying
goodbye), `purse` (what there is to spend, as a list rather than a field per
currency), `shop` (a trader's shelf with prices, one with no goods being the
stall closing), `error`.

Notably **not** in the `skills` frame: whether each skill can be afforded right
now. That changes every round while the frame is sent a few times an hour, so it
would be a stale answer to a question the client can settle itself, having the
cost in that frame and the energy in every `you`.

`bag` is separate from `you` on purpose: `you` goes out at every scratch, and a
bag that changes a few times an hour has no business riding along with it. It is
also the one frame that does **not** omit its empty fields — an empty bag is
news, and a `carried` that is simply missing cannot be told from one that was
never sent.

A delta carries arrivals, departures, movement, chat, presence, blows struck,
deaths and changes of fight state. Its consumer applies arrivals and departures
*last*: a delta says what happened and then what the world looks like now, and a
death is both at once — the fatal blow and the re-announcement of a character
already back at the spawn travel together.

Two limits apply to anything a client sends, both there to stop one socket from
degrading the map for everyone on it. A socket may send 30 frames per second and
is dropped above that. Move requests are collected during a tick and resolved
once at the end of it, so clicking faster buys later destinations, never more
pathfinding.

## What is not here yet

**One skill each, and only one shape of skill.** Nothing heals, nothing shields,
nothing lasts more than the round it happens in, and no skill requires another
first. Every one of those is its own balance question; this milestone only had
to show that a resource and the spending of it work at all.

**Intellect still does nothing for two of the three classes** — see above. That
is the most obvious thing left crooked.
**No rolled bonuses or rarities** — an item is exactly its definition. **No loot
on the ground**, no trading and no shops: a reward goes straight into the bag.
**No PvP**; `attack` refuses anything that is not a creature.

**NPCs talk and heal, and nothing else yet.** Storage, training, shops,
teleports and quests are all declared and all refused at startup — see above.
They arrive one at a time.

**Healing is free and has no cost of any kind.** Potions to buy and items that
may mend you after a fight are both planned; the first needs currency and the
second needs a new shape of bonus on an item.

**Nobody teaches anything at all** — see above; masters discount and undo, they
do not sell ranks.

**The account's chest can be made bigger and nothing can pay for it.** Room in a
character's own chest costs gold; room in the shared one costs a premium
currency that exists in the registry, is shown in the purse, and is granted by
nothing at all — no creature drops it and no NPC pays it. The first shared tab
is free, so the shelf works from the first day; the button beside it is built
and unreachable, the same deliberate state as the door thresholds below.

**Money in a chest is a convenience, not a safeguard.** Dying costs nothing but
time, so putting gold away protects it from nothing. It becomes a decision the
day death takes something.

**Prices are hand-written and barely balanced.** An item's worth is one number
in its own file, which is the right shape; whether the numbers are any good has
had exactly one pass.

**A door can ask for a maximum level, and no shipped door does.** The other two
thresholds are used now — the cave opens from level 6 and takes a torch — but
`untilLevel`, a way in for beginners that stops being one, is still only
exercised on fixture maps. Deliberate rather than forgotten.

**The browser checks cannot walk through the cave door.** The character an
end-to-end run produces reaches about level 3, and the cave opens from 6, so
what a browser proves is the door advertising its cost, the question naming it,
the threshold refusing with a reason, and a yes from the wrong tile being
refused by the server. The ticket actually burning is proven at the loop level,
by eight tests with two map threads running.

**Nothing costs money to walk through.** A toll taken by a tile is the first
thing in the game that would take coins without a click on a price, so it waits
for the conversation at a door — and so does `NpcFunction.TELEPORT`, still
refused at startup, because an NPC who moves you somewhere is that conversation
by another name.

Also missing: instances with parties. No password reset or email confirmation
either — both need to send mail, which means a service to run.

Roughly in order: potions, then the remaining ways through a door. Instances and
parties last, which is what heroes and colossi are waiting on.

## Layout

```
world/       maps, creatures, items and classes — immutable, shared
path/        A* over the collision grid    — server-side only
combat/      attributes, energy, damage, experience — pure functions, injected randomness
loop/        the game loop, commands, creature behaviour — no Spring below this line
account/     registration, login, sessions — passwords never reach the loop
net/         WebSocket transport           — authenticates, then decides nothing
persistence/ the only place with SQL       — never called from a map thread
protocol/    the wire format
e2e/         browser checks                — needs a running server
```
