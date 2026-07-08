# Snake & Ladder — Low-Level Design (OOD)

> A classic **object-oriented design (LLD)** problem. Model a board game: an N×N board with **snakes** and **ladders**, multiple **players** taking turns rolling a **dice**, moving until someone reaches the last cell. Interviewers grade your **class model, extensibility, and design patterns** — not scale.

> **How to read this doc:** each section has the dense interview summary first, then a **deep dive** (annotated Java and the exact confusions that come up while learning). Skim the summaries for revision; read the deep dives to actually understand the design.

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

### What are we actually building?

The physical board game. There's a **printed board** with numbered squares (1 to 100), some squares have a **ladder** drawn on them (land there → climb up, shortcut forward) and some have a **snake** (land there → slide down, penalty backward). Everyone puts a **token** on square 0, and you take turns **rolling a die** and moving your token forward. First token to reach square 100 wins.

Our job is **not** to make a fast, scalable web service. Our job is to write the *rules of the game* as clean, well-organized classes — so cleanly that a new rule ("give an extra turn when you roll a 6") can be bolted on without rewriting everything. That's what "low-level design" (LLD) means here: **good class modeling**, not big traffic.

#### Q: Why is this called a "design" problem if it's just a kids' game?

Because the interviewer isn't checking "can you make the game work" — a beginner can do that with one giant `main()` method full of `if`s. They're checking whether you can **split responsibilities into objects** (who owns what?), **name things well**, and **leave room to grow**. A messy `main()` and a clean class model both "play the game"; only one gets you the job.

#### Q: What do "functional" vs "non-functional" requirements mean?

- **Functional** = *what the game does* — the visible rules (roll, move, snakes bite, ladders lift, someone wins). If you removed one, the game would behave differently.
- **Non-functional** = *qualities of the code* — extensible, testable, deterministic. The player never "sees" these, but they decide whether your design is good. "Deterministic given a dice seed" just means: if you fix the die's random seed, the same game replays identically every time (crucial for testing).

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

### Turning the physical game into objects

The trick in any OOD problem is: **look at the real thing, and give each "noun" its own class.** Walk through a real Snake & Ladder box and the objects fall out on their own:

| Real-world thing | Class | What it's responsible for |
| --- | --- | --- |
| The whole game session, the rulebook | `Game` | Runs the turns, knows when someone won |
| The printed board | `Board` | Holds all 100 squares + where snakes/ladders are |
| One numbered square | `Cell` | Its position; maybe a snake/ladder attached |
| A snake or a ladder | `Jump` | "If you land here, you get moved to *there*" |
| A person playing | `Player` | Their name + which square their token is on |
| The die you roll | `Dice` | Produces a number 1–6 |

Notice the **"has-a" relationships** (the arrows in the diagram above): a `Game` *has a* `Board`, *has* `Player`s, *has a* `Dice`. A `Board` *has* `Cell`s. A `Cell` *may have a* `Jump`. This "has-a" wiring is called **composition** — building a big object out of smaller ones, like the game box containing the board, which contains the squares.

#### Q: Why is a snake and a ladder the SAME entity (`Jump`)? They feel opposite.

This is *the* clever insight of the whole problem. Physically a snake and a ladder look opposite (one drags you down, one lifts you up), but in code they do the **exact same thing**: "if you land on square X, you actually end up on square Y." The only difference is the *direction*:

```
Ladder: land on 5  → end up on 25   (end > start, you moved FORWARD)
Snake:  land on 47 → end up on 12   (end < start, you moved BACKWARD)
```

So instead of writing two classes with duplicated logic, we write **one `Jump` class** with a `start` and an `end`. Whether it's a snake or a ladder is just whether `end` is smaller or bigger than `start`. One idea, no duplication. (More on this in §3.)

#### Q: Why does `Player.position` start at 0 and not 1?

Square 0 is "off the board / at the starting line" — the token hasn't entered square 1 yet. On the first roll of a 3, the player moves from 0 to 3. It's a common convention; starting at 1 would also work but then your very first move math gets slightly awkward.

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

### Reading the classes one at a time

The summary above shows all the classes crammed together. Let's walk each one slowly, building up from the small pieces to the whole: first the dice, then the snakes/ladders (`Jump`), then the cells, then the players, then the `Game` that ties it together.

#### `Dice` — the random number maker

```java
class Dice {
    private final int count;                 // how many dice (usually 1)
    Dice(int count) { this.count = count; }

    int roll() {
        int total = 0;
        for (int i = 0; i < count; i++) {
            total += 1 + new Random().nextInt(6);   // each die → 1..6
        }
        return total;                        // e.g. 2 dice → 2..12
    }
}
```

That's it — its *only* job is "give me a number." Keeping it as its own class means later we can swap in a **fake dice** that always returns 4 (great for testing) without touching the game.

#### `Jump` — the one abstraction for snakes AND ladders

```java
class Jump {                    // unifies snake AND ladder
    int start;                  // the cell you land on
    int end;                    // where you actually get taken
    // end < start  → SNAKE  (slides you back)
    // end > start  → LADDER (lifts you forward)
}
```

No `isSnake` boolean, no separate `Snake`/`Ladder` classes. The numbers speak for themselves.

#### `Cell` — one square on the board

```java
class Cell {
    int position;               // 1..100
    Jump jump;                  // null = plain square; non-null = has a snake or ladder
}
```

A cell is mostly "am I special?" — if `jump` is `null` it's an ordinary square; otherwise landing here triggers the jump.

#### `Player` — a token on the board

```java
class Player {
    String id;
    String name;
    int position = 0;           // 0 = start line, before square 1
}
```

#### `Board` — the grid, and the brain for "where do I end up?"

```java
class Board {
    int size;                   // e.g. 100
    Cell[] cells;

    Board(int size, List<Jump> snakes, List<Jump> ladders) {
        // build every cell, then stick each snake/ladder onto its start cell
    }

    // Given the square a player LANDED on, return where they REALLY end up.
    int getFinalPosition(int landed) {
        if (landed > size) return -1;                 // overshot the end (exact-win rule)
        Cell c = cells[landed];
        return (c.jump != null) ? c.jump.end : landed; // jump if present, else stay put
    }
}
```

The key method is `getFinalPosition`. The player says "I rolled and landed on 5"; the board answers "there's a ladder on 5, so you're actually on 25." **All the snake/ladder magic lives in this one method** — the game loop doesn't need to know how it works.

#### `Game` — the rulebook that runs everything

```java
class Game {
    private final Board board;
    private final Dice dice;
    private final Deque<Player> players;    // turn queue (round-robin)
    private Player winner;

    void play() {
        while (winner == null) {
            Player p = players.poll();          // 1. whose turn is it? take from front
            int roll = dice.roll();             // 2. roll the die
            int target = p.position + roll;     // 3. tentative landing square
            int finalPos = board.getFinalPosition(target);  // 4. apply snake/ladder

            if (finalPos == board.size) { winner = p; break; } // 5a. reached 100 → win
            if (finalPos != -1) p.position = finalPos;         // 5b. valid move → update
            players.offer(p);                   // 6. put player at back of the queue
        }
    }
}
```

`Game` doesn't roll dice itself, doesn't compute jumps itself — it **delegates**: asks `Dice` to roll, asks `Board` where the token ends up. It only orchestrates the *order* of things. This is the heart of good OOD: **each class does one job; the coordinator just wires them together.**

#### Q: Why give each thing its own class instead of one big `Game` class doing everything?

Because **change is easier when responsibilities are separate.** Want two dice? Change `Dice`, nothing else. Want a bigger board? Change `Board`. Want a new win rule? Touch only the win check. If all of this lived in one 300-line `play()` method, every tweak risks breaking something unrelated. This is the **Single Responsibility Principle** — one class, one reason to change.

#### Q: What is `Deque<Player>` and why use it for turns?

A `Deque` ("deck", double-ended queue) lets you take from the **front** and add to the **back**. That's exactly a turn order: `poll()` grabs the player whose turn it is (front), and after they move, `offer(p)` puts them at the back to wait for their next turn. Everyone cycles round and round — a perfect **round-robin**.

```
Front [ Alice, Bob, Carol ] Back
  Alice's turn → poll() → Alice moves → offer(Alice)
Front [ Bob, Carol, Alice ] Back
  Bob's turn → ...
```

#### Q: What does `finalPos == -1` mean?

It's the "you overshot" signal. Under the *exact-win* rule, if you're on square 98 and roll a 5, you'd land on 103 — past the finish. `getFinalPosition` returns `-1` to mean "illegal move, don't move at all." The game then just requeues the player without changing their position, so they try again next turn.

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

### One turn, step by step

Each round the game loop runs the same steps, over and over, until someone wins:

```
1. "Whose turn?"     → take the next player from the front of the line
2. "Roll!"           → player rolls the die (say, a 4)
3. "Move your token" → new square = current square + 4
4. "Check the board" → snake here? ladder here? off you go
5. "Did you win?"    → landed exactly on 100? game over
6. "Back of the line"→ otherwise, wait for your next turn
```

The loop is deliberately **boring and repetitive** — that's good. A game is just these steps repeated. Here it is fully annotated in Java, mirroring the pseudo-code above:

```java
void play() {
    while (winner == null) {                       // keep going until someone wins
        Player p = players.poll();                 // 1. front of the queue = current player
        int roll = dice.roll();                    // 2. roll (1..6)
        int target = p.position + roll;            // 3. tentative square

        // 4. exact-win rule: rolling past the last square is not allowed
        if (target > board.size) {
            players.offer(p);                      //    no move; requeue and try next turn
            continue;
        }

        int finalPos = board.getFinalPosition(target); // 4b. snake/ladder applied
        p.position = finalPos;                     // 5. actually move the token

        if (finalPos == board.size) {              // 6a. landed exactly on 100
            winner = p;
            break;                                 //     end the game
        }
        players.offer(p);                          // 6b. otherwise, back of the line
        // rule hook: if (roll == 6) don't requeue at the back → let them roll again
    }
}
```

#### Q: What does "rule hooks (Strategy)" mean in the bullet above?

A **rule hook** is a spot in the loop where the *behavior can be swapped out*. "Do you need an exact roll to win, or is overshooting fine?" is a rule that different game variants answer differently. Instead of hard-coding `if (target > size)` forever, you can pull that decision into a separate little object (a **Strategy** — see §5) and plug in whichever version you want. The loop stays the same; only the plugged-in rule changes.

#### Q: Won't the `while` loop run forever if nobody can reach exactly 100?

In theory a very unlucky game with the strict exact-win rule could bounce near the end for a while, but statistically someone eventually rolls the exact number needed, so it terminates. If you were worried about true infinite loops (e.g. a broken board with a snake that sends you back to a ladder that sends you forward to the same snake), you'd add a safeguard like a maximum turn count — but for the classic game it always ends.

#### Q: Why compute `target` first and *then* apply the jump — why two steps?

Because a jump only triggers on the square you **land** on, not the squares you pass over. If there's a snake on square 7 and you're on 4 rolling a 5, you land on 9 (you *passed over* 7 but didn't land on it) — no snake. So step 3 finds the landing square, and step 4 asks "is there a jump *on that exact square*?" Separating them keeps this rule correct and obvious.

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

### Patterns are just named good ideas

A **design pattern** is a reusable solution to a common problem — a *name* for a trick experienced developers keep reinventing. You don't need all nine; interviewers want to hear you say the right 3–4 for *this* problem and show one in code. Below are the ones that genuinely help here, in plain terms.

#### Strategy — swap a rule without touching the loop

Two variants of the same game: one requires landing **exactly** on 100 to win, the other lets you **reach or pass** 100. Same game, one rule differs. Strategy = wrap that rule in its own object so you can plug in either version.

```java
// The "hook": an interface describing ONE swappable rule.
interface WinStrategy {
    boolean hasWon(int position, int boardSize);
}

// Variant 1: must land EXACTLY on the last square
class ExactWinStrategy implements WinStrategy {
    public boolean hasWon(int position, int boardSize) {
        return position == boardSize;
    }
}

// Variant 2: reaching OR passing the end counts
class OvershootWinStrategy implements WinStrategy {
    public boolean hasWon(int position, int boardSize) {
        return position >= boardSize;
    }
}
```

`Game` just holds a `WinStrategy` and asks it — it never knows *which* rule is plugged in:

```java
class Game {
    private final WinStrategy winStrategy;   // injected once at setup

    // inside the loop:
    // if (winStrategy.hasWon(p.position, board.size)) { winner = p; break; }
}
```

Swapping `new ExactWinStrategy()` for `new OvershootWinStrategy()` changes the game **without editing the loop at all.** That's the whole point.

#### Builder / Factory — assemble a complicated board readably

Rather than a constructor with ten arguments (easy to mess up the order), a **Builder** lets you spell out each choice by name, then `build()`.

```java
class BoardBuilder {
    private int size = 100;
    private final List<Jump> jumps = new ArrayList<>();

    BoardBuilder size(int n)               { this.size = n; return this; }
    BoardBuilder snake(int start, int end) { jumps.add(new Jump(start, end)); return this; } // end<start
    BoardBuilder ladder(int start, int end){ jumps.add(new Jump(start, end)); return this; } // end>start
    Board build()                          { return new Board(size, jumps); }
}

// Reads naturally:
Board board = new BoardBuilder()
        .size(100)
        .ladder(5, 25)
        .snake(47, 12)
        .build();
```

A **Factory** is the simpler cousin: a method like `createStandardBoard()` that hands back a pre-configured classic board so callers don't repeat setup.

#### Observer — tell the outside world when something happens

The game plays on; separately, observers (UI, logger, scoreboard) *watch* and react to each move — without the game caring who's listening.

```java
interface MoveObserver {
    void onMove(Player p, int from, int to);   // called after every move
}

class Game {
    private final List<MoveObserver> observers = new ArrayList<>();
    void addObserver(MoveObserver o) { observers.add(o); }

    private void notifyMove(Player p, int from, int to) {
        for (MoveObserver o : observers) o.onMove(p, from, to);  // ping everyone
    }
}

// A logger that just prints — the game doesn't know or care it exists
class ConsoleLogger implements MoveObserver {
    public void onMove(Player p, int from, int to) {
        System.out.println(p.name + " moved " + from + " → " + to);
    }
}
```

This **decouples** the game rules from how you display them — you can add a GUI later by writing a new observer, changing zero game code.

#### Q: Do I need to memorize all nine patterns for the interview?

No. Lead with the **big three for this problem**: Strategy (rules), Builder/Factory (board setup), and Observer (move notifications) — plus mention State (game lifecycle) and Singleton (one game session) if asked. Naming a pattern you can't explain is worse than naming fewer that you can code. The table above is a menu, not a checklist to recite.

#### Q: Isn't using patterns overkill for a kids' game?

For a throwaway script, yes. But the interviewer is testing whether you *recognize where a pattern fits* — the exact skill that keeps real, long-lived codebases maintainable. The trick is applying them **where change is likely** (rules, setup, notifications) and not forcing them where they add noise.

---

## 6. Extensibility

- **New entity types** (e.g., "teleport", "mine") → add a `Jump`/effect subtype; the loop is unchanged.
- **Different board size / multiple dice** → constructor params.
- **New win rules** → new `WinStrategy`.
- **Networked multiplayer** → wrap `Game` behind a server; sync state (turn, positions) to clients over WebSocket; server is authoritative (prevents cheating). Persist game state (below).

### "Extensible" = new features without surgery

The real test of a good design: *can I add a feature by writing new code, instead of rewriting old code?* The gold standard is the **Open/Closed Principle** — code should be **open** to new behavior but **closed** to being edited.

#### Example — adding a "teleport" tile without touching the loop

Say we want a new tile: land on it and jump to a *random* square. Because a jump's destination is decided by the `Board`, we can extend the idea of a jump to compute its end dynamically, and the game loop never changes:

```java
// A jump whose destination is computed on the fly, not fixed.
class TeleportJump extends Jump {
    private final int boardSize;
    TeleportJump(int start, int boardSize) {
        this.start = start;
        this.boardSize = boardSize;
    }
    int resolveEnd() {                     // new behavior lives HERE
        return 1 + new Random().nextInt(boardSize);
    }
}
```

The `play()` loop still just calls `board.getFinalPosition(target)` — it has no idea "teleport" now exists. **That's extensibility:** the new mechanic slots in behind the same method the loop already uses.

#### Q: What's the difference between "extensible" and "just add an if-statement"?

Adding `if (tile == TELEPORT) ...` into the game loop *works*, but every new mechanic makes the loop longer and more fragile — soon it's a tangle of special cases (this is the "big ball of mud" anti-pattern). Extensible design instead adds a **new small class** (a new jump type, a new strategy) and leaves the loop untouched. Fewer edits to tested code = fewer new bugs.

#### Q: How does single-player LLD turn into online multiplayer?

You wrap the same `Game` object inside a **server**. The key change is that the *server* becomes the single source of truth ("**server-authoritative**"): players send "I want to roll" over a WebSocket, the server does the roll and move, then broadcasts the new positions to everyone. Why authoritative? So a cheating client can't just claim "I rolled a 6 and won" — only the server rolls. The core class model you already built doesn't change; you're adding a networking layer around it and saving state to a database (next section).

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

### Why save the game to a database at all?

For a single-player program on one machine, you don't — everything lives in memory (RAM) and vanishes when the program ends, which is fine. You only need a **database** when the game must **survive** beyond the program run: online multiplayer where a player disconnects and rejoins, or "resume your saved game later."

#### Q: How do the classes map to these tables?

Each object type gets a table; each object instance becomes a **row**. It's a fairly direct translation:

| In-memory object | Table | Notes |
| --- | --- | --- |
| `Game` (one session) | `game` | board size, status, whose turn, winner |
| each `Player` in a game | `game_player` | their `position` + `turn_order` |
| each `Jump` on the board | `board_jump` | `start_pos → end_pos`, type SNAKE/LADDER |
| each move that happened | `move_log` | one row per roll — the history |

```sql
-- A player's live position is just a number in a row we UPDATE each turn:
UPDATE game_player SET position = 25 WHERE game_id = 1 AND player_id = 7;
```

#### Q: What is `move_log` for — we already store positions?

`game_player.position` is the **current** state (where the token is *now*). `move_log` is the **history** (every roll and move ever made). You need history for two things: **replay** (watch the whole game back move-by-move, like a chess game record) and **audit** (prove no cheating happened). Current state answers "where are we?"; the log answers "how did we get here?"

#### Q: Why mention Redis for online play instead of just the SQL database?

A regular SQL database is durable but relatively slow, and multiplayer turn state changes constantly (whose turn, room membership, are players still connected). **Redis** is an in-memory store — extremely fast reads/writes — perfect for this hot, frequently-changing "room" state. You keep the *permanent* record (finished games, move history) in SQL, and the *live, fast-changing* turn/room state in Redis. It's the classic split: durable store for truth, fast cache for live coordination.

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
