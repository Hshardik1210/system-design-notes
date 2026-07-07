# 📬 Pub-Sub / Message Queue

A broker with topics, subscribers, and consumer groups — two delivery models.

## Delivery models
### PUSH (Observer)
A subscriber registers a callback on a topic; `publish()` **fans out** the message to every subscriber immediately. Good for in-process event buses.

### PULL with offsets (Kafka-style)
Each topic is an **append-only log**. A **consumer group** tracks its own read **offset** and polls new messages. Different groups read the *same* log **independently** (broadcast across groups; within a group you'd load-balance across members).

```
publish ─▶ [log: m1 m2 m3 m4]
groupA.offset ──▶ reads m1..m4, commits offset=4
groupB.offset ──▶ reads m1..m4 independently
```

## Design patterns
| Pattern | Where | Why |
| --- | --- | --- |
| Observer | push subscribers | Immediate fan-out |
| Commit log + offsets | pull consumer groups | Replayable, independent consumers |

> Partitions, ordering, and delivery semantics are covered in `concepts/kafka.md`.

## Run
```bash
cd java && javac Main.java && java Main
cd cpp && g++ -std=c++17 main.cpp -o main && ./main
```
