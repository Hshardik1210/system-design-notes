# 🔨 Online Auction System

Bidding with a highest-bid rule, an auction lifecycle, and outbid notifications.

## Rules
- Each auction has a **start price** and **minimum increment**.
- A bid must be `≥ max(startPrice, highestBid + increment)`.
- Bids accepted only while **OPEN**; `close()` declares the highest bidder the winner (or "no sale").

## Concurrency
Bids are **mutex-guarded**: the "is this bid high enough? → set new highest" is one critical section, so two simultaneous bids can't both beat a stale highest value (no lost updates).

## Observer
When a new bid takes the lead, the **previous leader is notified** they've been outbid — a classic Observer callback.

## Design patterns
| Pattern | Where | Why |
| --- | --- | --- |
| State | `AuctionState` | OPEN → CLOSED lifecycle |
| Observer | outbid notifications | Decoupled participant updates |

## Run
```bash
cd java && javac Main.java && java Main
cd cpp && g++ -std=c++17 main.cpp -o main && ./main
```
