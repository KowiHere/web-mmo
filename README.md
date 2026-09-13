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

Creatures are never saved. They are derived from map data, so a restart brings
them back by itself — and a creature has no account to own it, so writing one
would violate the very foreign key that keeps characters honest.

### Mobs and NPCs are different things

A **mob** is hostile: combat statistics, behaviour, a respawn timer, loot. It
dies. An **NPC** is something you interact with — dialogue, a shop, a quest —
with no combat statistics, no respawn, and nothing to kill. Only mobs exist so
far; NPCs arrive as their own kind of actor rather than as a mob with its
aggression switched off.

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
| click | walk to that tile |
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
server refusing an illegal destination, and a dropped socket resuming the same
character.

One check needs the server stopped and started around it, so it runs itself:

```bash
./mvnw package -DskipTests
e2e/restart-check.sh
```

It registers an account, walks the character somewhere, kills the server,
starts it again, and then **logs in** rather than registering — so it fails
unless both the account and the character survived. Nothing inside a single
process can prove that.

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
| `chat`   | text           | say something on this map              |

Server to client: `init` (the whole world once), `delta` (what changed), `error`.

Two limits apply to anything a client sends, both there to stop one socket from
degrading the map for everyone on it. A socket may send 30 frames per second and
is dropped above that. Move requests are collected during a tick and resolved
once at the end of it, so clicking faster buys later destinations, never more
pathfinding.

## What is not here yet

**No combat**, which is the honest limit of the creatures above: nothing can be
killed, so their statistics are carried but unread, their respawn timers never
fire, and the difference between a mob and an elite is for now how it arrives
and how it looks rather than how it fights. Loot tables are deliberately absent
until items exist — a table pointing at a registry that does not exist cannot
even be validated.

Also missing: items, interactive NPCs, more than one map, and instances with
parties. No password reset or email confirmation either — both need to send
mail, which means a service to run.

Roughly in order: turn-based combat on timers, items with rolled statistics and
loot, interactive NPCs, then instances and parties, which is what heroes and
colossi are waiting on.

## Layout

```
world/       maps and creature definitions — immutable, shared
path/        A* over the collision grid    — server-side only
loop/        the game loop, commands, creature behaviour — no Spring below this line
account/     registration, login, sessions — passwords never reach the loop
net/         WebSocket transport           — authenticates, then decides nothing
persistence/ the only place with SQL       — never called from a map thread
protocol/    the wire format
e2e/         browser checks                — needs a running server
```
