# 🧠 LRU Cache

A fixed-capacity cache that evicts the **least-recently-used** entry when full. Both `get` and `put` must be **O(1)**.

## Key idea
Combine two data structures:
- **HashMap** `key → node` — O(1) lookup.
- **Doubly linked list** — O(1) move-to-front and remove-from-tail.

The list keeps usage order: **front = most-recently-used**, **tail = least-recently-used**. On every access we move the node to the front; on eviction we drop the tail.

We hand-build the linked list with **dummy head/tail sentinels** so there are no null-pointer edge cases at the ends — this is exactly what interviewers look for (don't just use `LinkedHashMap`).

> Note: Java's `LinkedHashMap(accessOrder=true)` gives this for free, but implementing it manually demonstrates understanding.

## Complexity
| Op | Time | Space |
| --- | --- | --- |
| get | O(1) | O(capacity) |
| put | O(1) | O(capacity) |

## Run
```bash
cd java && javac Main.java && java Main
cd cpp && g++ -std=c++17 main.cpp -o main && ./main
```
