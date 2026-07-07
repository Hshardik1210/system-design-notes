# ✍️ Text Editor (Undo / Redo)

A text buffer with unlimited undo/redo — the canonical **Command pattern** problem.

## Design
- Every mutation is a **Command** with `execute()` and `undo()`:
  - `InsertCommand` — undo = delete what was inserted.
  - `DeleteCommand` — captures the removed text on `execute()` (a lightweight **Memento**), so undo can reinsert it.
- The **Editor** keeps two stacks:
  - `undoStack` — executed commands.
  - `redoStack` — commands that were undone.
- `undo()` pops from undo, reverses it, pushes to redo. `redo()` does the opposite.
- Running a **new** command **clears the redo stack** — once you diverge from history, the old redo path is gone (exactly like real editors).

## Design patterns
| Pattern | Where | Why |
| --- | --- | --- |
| Command | `Command` + Insert/Delete | Encapsulate reversible actions |
| Memento | `DeleteCommand.removed` | Capture state needed to reverse |

## Run
```bash
cd java && javac Main.java && java Main
cd cpp && g++ -std=c++17 main.cpp -o main && ./main
```
