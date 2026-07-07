# 📝 Logging Framework

A mini log4j/SLF4J: log at levels (INFO/WARN/ERROR), route to multiple sinks with per-sink level thresholds.

## Two patterns combined
- **Chain of Responsibility** — handlers are chained by their **minimum level**. A message flows down the chain; each handler whose threshold is met writes it, then passes it on. Add/remove links without touching the rest.
- **Strategy** — an **Appender** decides *where* the line goes (console, file, network…), independent of the level logic.

### Example chain
```
message ─▶ [INFO+ → Console] ─▶ [ERROR+ → File] ─▶ (end)
```
- `INFO`/`WARN` → console only.
- `ERROR` → console **and** file.

## Design patterns
| Pattern | Where | Why |
| --- | --- | --- |
| Chain of Responsibility | `LogHandler` chain | Level-based routing |
| Strategy | `Appender` | Pluggable sinks |
| Facade | `Logger` | Simple `info/warn/error` API |

## Run
```bash
cd java && javac Main.java && java Main
cd cpp && g++ -std=c++17 main.cpp -o main && ./main
```
