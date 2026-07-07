# 🔔 Notification System

Send a notification to a user across multiple channels (Email/SMS/Push), rendered from a template, with retries on failure.

## Design
- **Strategy** — every `Channel` is an interchangeable sender with the same `send()` contract. Add WhatsApp/Slack by adding a class.
- **Observer** — a user *subscribes* to channels; the dispatcher **fans out** one notification to each subscribed channel.
- **Template** — messages come from a pattern (`"Hi {name}, order {orderId} shipped"`) rendered with variables, so content is decoupled from delivery.
- **Retry** — transient send failures are retried up to `maxRetries` (the demo's SMS channel fails once then succeeds). Real systems push failures to a **DLQ** after exhausting retries.

## Design patterns
| Pattern | Where | Why |
| --- | --- | --- |
| Strategy | `Channel` | Pluggable delivery channels |
| Observer | user subscriptions → fan-out | Decoupled multi-channel dispatch |
| Template Method (data) | `Template` | Content vs delivery separation |

> Production adds a queue (Kafka), per-channel workers, idempotency keys, and rate limits — see `system-design/notification-system-design.md`.

## Run
```bash
cd java && javac Main.java && java Main
cd cpp && g++ -std=c++17 main.cpp -o main && ./main
```
