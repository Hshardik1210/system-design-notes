# 🏏 CricBuzz / Live Scoreboard

Apply ball-by-ball cricket scoring rules and push live updates to subscribers.

## Scoring rules
| Ball | Runs | Legal ball? |
| --- | --- | --- |
| RUN (0–6) | + runs | ✅ counts toward the over |
| WIDE | +1 | ❌ re-bowled |
| NO_BALL | +1 | ❌ re-bowled |
| WICKET | 0 | ✅, wickets++ |

`6 legal balls = 1 over`; overs are shown as `overs.balls` (e.g. `1.2`).

## Observer
The `Innings` is the **subject**; a scoreboard view and a commentary/alert feed **subscribe** and are notified after every ball. Add more views (graphs, push notifications) without changing the scoring logic.

## Design patterns
| Pattern | Where | Why |
| --- | --- | --- |
| Observer | scoreboard subscribers | Live fan-out of updates |

## Run
```bash
cd java && javac Main.java && java Main
cd cpp && g++ -std=c++17 main.cpp -o main && ./main
```
