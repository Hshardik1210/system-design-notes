# 🗄️ In-Memory Key-Value Store (with nested transactions)

`set / get / delete` plus `BEGIN / COMMIT / ROLLBACK` that can **nest**.

## How nested transactions work
- `committed` holds the live state; mutations apply to it immediately.
- Each `BEGIN` pushes an **undo log** onto a stack. Before a key is first changed inside a transaction, its **previous value** is recorded there (empty ⇒ "was absent").
- `ROLLBACK` pops the top undo log and restores every recorded key.
- `COMMIT` pops the top log and **merges it into the parent** (using `putIfAbsent` so the parent keeps the value from before *it* began). At the outermost level, changes are already durable.

This gives **O(1)** `set`/`get` and correct semantics for arbitrary nesting — a rollback of an inner transaction leaves the outer one intact.

## Example
```
set a 1            a=1
BEGIN              (TX1)
  set a 2          a=2
  BEGIN            (TX2)
    set a 3        a=3
  ROLLBACK         a=2   (TX2 reverted)
COMMIT             a=2   (permanent)
```

## Run
```bash
cd java && javac Main.java && java Main
cd cpp && g++ -std=c++17 main.cpp -o main && ./main
```
