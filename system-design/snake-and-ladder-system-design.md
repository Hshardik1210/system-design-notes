# Snake & Ladder — Low-Level Design (OOD)

> A classic **object-oriented design (LLD)** problem. Model a board game: an N×N board with **snakes** and **ladders**, multiple **players** taking turns rolling a **dice**, moving until someone reaches the last cell. Interviewers grade your **class model, extensibility, and design patterns** — not scale.

---

## Contents

- [1. Requirements](#1-requirements)
- [2. Core Entities](#2-core-entities)
- [3. Class Design](#3-class-design)
- [4. Game Loop / Algorithm](#4-game-loop--algorithm)
- [5. Design Patterns (that can be used)](#5-design-patterns-that-can-be-used)
- [6. Extensibility](#6-extensibility)
- [7. Data Model (if persisted)](#7-data-model-if-persisted)
- [8. Interview Cheat Sheet](#8-interview-cheat-sheet)
- [9. Final Takeaways](#9-final-takeaways)

---

## 1. Requirements

### Functional
- Board of **N×N** cells (typically 100); configurable **snakes** and **ladders** (start → end).
- 2+ **players**; take **turns** rolling a dice (1–6, configurable count).
- Move a player by the dice value; if landing on a snake head → slide down; on a ladder bottom → climb up.
- First player to reach (or exceed / land exactly on — clarify) the **last cell** wins.
- Support rules: exact roll to win, extra turn on a 6, multiple dice.

### Non-functional
- **Extensible** (new entities/rules), clean OO model, testable, deterministic given a dice seed.

> **Clarify:** exact landing to win, or overshoot allowed? Extra turn on 6? Number of dice? Single machine (LLD) or networked multiplayer (adds a server + state sync)?

---

## 2. Core Entities

```
Game ── has a ─► Board ── has ─► Cells, each Cell may hold a Snake or Ladder (a "Jump")
Game ── has ─► Players (queue for turns), a Dice
```

| Entity | Role |
| --- | --- |
| `Game` | Orchestrates turns, holds board/players/dice, decides winner |
| `Board` | N×N cells; stores snakes & ladders; computes destination after a move |
| `Cell` | A position; optionally has a `Jump` (snake or ladder) |
| `Jump` | start → end (snake: end < start; ladder: end > start) |
| `Player` | id, name, current position |
| `Dice` | rolls 1..6 (× number of dice) |

---

## 3. Class Design

```java
class Dice {
    private final int count;
    Dice(int count) { this.count = count; }
    int roll() { /* sum of `count` random 1..6 */ }
}

class Jump {                    // unifies snake AND ladder
    int start;                  // cell you land on
    int end;                    // where you're taken (end<start = snake, end>start = ladder)
}

class Cell {
    int position;
    Jump jump;                  // null if plain cell
}

class Player {
    String id;
    String name;
    int position = 0;           // 0 = off-board / start
}

class Board {
    int size;                   // e.g. 100
    Cell[] cells;
    Board(int size, List<Jump> snakes, List<Jump> ladders) {
        // build cells; attach jumps at their start positions
    }
    int getFinalPosition(int landed) {           // apply snake/ladder if present
        if (landed > size) return -1;            // overshoot (if exact-win rule)
        Cell c = cells[landed];
        return (c.jump != null) ? c.jump.end : landed;
    }
}

class Game {
    private final Board board;
    private final Dice dice;
    private final Deque<Player> players;         // turn queue (round-robin)
    private Player winner;

    void play() {
        while (winner == null) {
            Player p = players.poll();            // whose turn
            int roll = dice.roll();
            int target = p.position + roll;
            int finalPos = board.getFinalPosition(target);
            if (finalPos == board.size) { winner = p; break; }   // win
            if (finalPos != -1) p.position = finalPos;           // else overshoot → stay
            players.offer(p);                     // back of the queue
            // (rule hooks: extra turn on 6, etc.)
        }
    }
}
```

> **Key modeling insight:** represent **snakes and ladders as one `Jump` type** (start→end). A snake is a jump with `end < start`; a ladder has `end > start`. One abstraction, no duplicate logic.

---

## 4. Game Loop / Algorithm

```
play():
    while no winner:
        player = nextInTurnQueue()
        roll = dice.roll()
        target = player.position + roll
        if target > boardSize:            # exact-win rule → overshoot, skip move
            requeue(player); continue
        final = board.applyJumpIfAny(target)   # snake/ladder
        player.position = final
        if final == boardSize: winner = player; break
        requeue(player)                    # (or give extra turn if roll==6)
```

- **Turn order** via a queue (round-robin).
- **Win condition** and **overshoot** behavior are rule hooks (Strategy).

---

## 5. Design Patterns (that can be used)

| Pattern | Where | Why |
| --- | --- | --- |
| **Strategy** | Win condition (exact vs overshoot), dice behavior, "extra turn on 6" | Swap rules without touching the game loop |
| **Factory** | Build `Board` (snakes/ladders), create players/dice | Centralize construction/config |
| **Singleton** | `Game` instance (single game session) | One authoritative game state |
| **State** | Game state (NOT_STARTED → RUNNING → FINISHED); player turn state | Guard legal transitions |
| **Observer** | Notify UI/log/scoreboard on each move/win | Decouple game from presentation |
| **Command** | Each move as a command (supports replay/undo) | Uniform, replayable actions |
| **Template Method** | Turn skeleton (roll → move → applyJump → checkWin), overridable steps | Reuse flow, vary rules |
| **Iterator** | Round-robin player turns | Clean turn traversal |
| **Builder** | `BoardBuilder` for flexible board/jump setup | Readable configuration |

> **Interview lead:** Strategy (rules), Factory/Builder (board/players setup), Singleton (game), State (lifecycle), Observer (move notifications). That set covers the follow-ups.

---

## 6. Extensibility

- **New entity types** (e.g., "teleport", "mine") → add a `Jump`/effect subtype; the loop is unchanged.
- **Different board size / multiple dice** → constructor params.
- **New win rules** → new `WinStrategy`.
- **Networked multiplayer** → wrap `Game` behind a server; sync state (turn, positions) to clients over WebSocket; server is authoritative (prevents cheating). Persist game state (below).

---

## 7. Data Model (if persisted)

```sql
CREATE TABLE game ( game_id BIGINT PRIMARY KEY, board_size INT, status VARCHAR(15),
                    current_turn BIGINT, winner_id BIGINT, created_at TIMESTAMP );
CREATE TABLE game_player ( game_id BIGINT, player_id BIGINT, position INT, turn_order INT,
                           PRIMARY KEY (game_id, player_id) );
CREATE TABLE board_jump ( game_id BIGINT, start_pos INT, end_pos INT, type VARCHAR(10),  -- SNAKE|LADDER
                          PRIMARY KEY (game_id, start_pos) );
CREATE TABLE move_log ( move_id BIGINT PRIMARY KEY, game_id BIGINT, player_id BIGINT,
                        dice INT, from_pos INT, to_pos INT, at TIMESTAMP );   -- replay/audit
```

> **Tables to consider:** game, game_player, board_jump, move_log (for replay/audit). For online multiplayer, add users, sessions, and a Redis room/turn state.

---

## 8. Interview Cheat Sheet

> **"How do you model snakes and ladders?"**
> "As one `Jump` abstraction (start→end): a snake has `end < start`, a ladder `end > start`. A `Cell` optionally holds a `Jump`; after a move, the board applies the jump if present — no duplicated snake/ladder logic."

> **"How do turns work?"**
> "A round-robin queue of players; each turn = roll dice, compute target, apply jump, check win, requeue. Rule hooks (extra turn on 6, exact-win overshoot) are Strategy objects."

> **"How would you make it extensible?"**
> "Strategy for win/dice/turn rules, Factory/Builder for board setup, new cell-effect subtypes for new mechanics. The game loop stays untouched."

> **"How would you make it online multiplayer?"**
> "Server-authoritative `Game` (prevents cheating), state synced to clients over WebSocket, game/turn state persisted (or in Redis), moves logged for replay."

---

## 9. Final Takeaways

- **One `Jump` abstraction** unifies snakes and ladders (start→end) — the key modeling move.
- **Round-robin turn queue**; win/overshoot/extra-turn are **Strategy** rule hooks.
- **Game (Singleton) orchestrates**; Board applies jumps; Dice rolls; Players hold position.
- Patterns: Strategy, Factory/Builder, Singleton, State, Observer, Command, Template Method.
- Persist game/players/jumps/move_log for replay; server-authoritative for online play.

### Related notes

- [Parking Lot — System Design (OOD/LLD)](parking-lot-system-design.md) — sibling OOD problem, similar pattern set
