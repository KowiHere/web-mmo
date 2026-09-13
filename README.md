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

## Running it

```bash
./mvnw spring-boot:run
```

Then open <http://localhost:8080> — and open it a second time in another tab to
see the multiplayer half actually working.

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

No accounts, no database, no combat, no items. Characters live in memory and are
gone when the process stops. That is deliberate — the world and its movement had
to be right before anything got stacked on top of them.

Roughly in order: accounts and persistence (PostgreSQL, written behind the tick
rather than during it), NPCs with spawn points and respawn timers, turn-based
combat on timers, items with rolled statistics, then multiple maps with gates
between them.

## Layout

```
world/     map definitions and collision   — immutable, shared
path/      A* over the collision grid      — server-side only
loop/      the game loop and its commands  — no Spring below this line
net/       WebSocket transport             — translates frames, decides nothing
protocol/  the wire format
e2e/       two-tab browser checks          — needs a running server
```
