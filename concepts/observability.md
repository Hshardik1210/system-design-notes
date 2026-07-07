# Observability — Logs, Metrics, Traces & SLOs

> **Goal:** understand what a distributed system is doing and why — to detect, diagnose, and prevent problems. The three pillars are **logs, metrics, traces**; the operational contract is **SLI / SLO / SLA**. Interviewers expect you to add an "observability" section to any design.

---

## Contents

- [1. Observability vs Monitoring](#1-observability-vs-monitoring)
- [2. The Three Pillars](#2-the-three-pillars)
- [3. Metrics](#3-metrics)
- [4. Logs](#4-logs)
- [5. Distributed Tracing](#5-distributed-tracing)
- [6. SLI / SLO / SLA & Error Budgets](#6-sli--slo--sla--error-budgets)
- [7. Alerting](#7-alerting)
- [8. Interview Cheat Sheet](#8-interview-cheat-sheet)
- [9. Final Takeaways](#9-final-takeaways)

---

## 1. Observability vs Monitoring

- **Monitoring** = watching known signals (dashboards/alerts for things you predicted).
- **Observability** = being able to ask **new questions** about the system from its outputs — diagnose problems you didn't anticipate.

> You monitor for known failure modes; observability lets you debug the unknown ones.

---

## 2. The Three Pillars

| Pillar | Answers | Example |
| --- | --- | --- |
| **Metrics** | "What / how much / how often?" (aggregate) | request rate, p99 latency, error % |
| **Logs** | "What exactly happened for this event?" | structured event with ids + error |
| **Traces** | "Where did the time/failure go across services?" | request path A→B→C with per-hop latency |

Together: metrics **detect**, traces **localize**, logs **explain**.

---

## 3. Metrics

Numeric time-series, cheap to store and aggregate.

| Type | Meaning |
| --- | --- |
| **Counter** | Monotonic (requests_total, errors_total) |
| **Gauge** | Point-in-time value (memory, queue depth, active conns) |
| **Histogram** | Distribution → **percentiles (p50/p95/p99)** |

- Track **RED** (Rate, Errors, Duration) for services and **USE** (Utilization, Saturation, Errors) for resources.
- **Percentiles > averages** — p99 latency reveals tail pain that an average hides.
- Tools: Prometheus, Grafana, Datadog. Beware **high-cardinality** labels (userId) blowing up storage.

---

## 4. Logs

Timestamped records of events.

- **Structured logs (JSON)** > plain text — queryable by fields.
- Always include correlation ids: `request_id`, `trace_id`, `user_id`, `service`.
- **Levels**: DEBUG/INFO/WARN/ERROR; sample high-volume logs to control cost.
- **Never log secrets/PII** (tokens, card numbers, message bodies).
- Ship to a central store (ELK/OpenSearch, Loki, Splunk) — don't grep boxes.

---

## 5. Distributed Tracing

Follows one request across many services.

```
trace_id = abc123
  span: gateway        [0–2ms]
    span: order-svc    [2–40ms]
      span: db query   [5–30ms]   ← the slow hop
      span: payment    [31–39ms]
```

- A **trace** = a tree of **spans**; each span = one operation with start/end + tags.
- **Context propagation**: the `trace_id` is passed via headers across service calls (W3C Trace Context).
- Pinpoints **which service/hop** caused latency or errors.
- Tools: OpenTelemetry (standard), Jaeger, Zipkin, Datadog APM. Usually **sampled** (e.g. 1%).

---

## 6. SLI / SLO / SLA & Error Budgets

| Term | Definition | Example |
| --- | --- | --- |
| **SLI** (Indicator) | A measured signal | success rate, p99 latency |
| **SLO** (Objective) | Internal target for an SLI | 99.9% success over 30 days |
| **SLA** (Agreement) | Contract with penalties | 99.9% uptime or refund |
| **Error budget** | Allowed failure = 1 − SLO | 99.9% → ~43 min/month down |

> **Error budget** balances reliability vs velocity: if you're within budget, ship features; if you've burned it, freeze and fix reliability. SLA ⊇ SLO (SLA looser, with legal teeth); set SLOs stricter than SLAs.

---

## 7. Alerting

- Alert on **symptoms users feel** (error rate, latency, budget burn), not every low-level metric.
- Page on **actionable, urgent** issues; everything else → dashboards/tickets.
- Avoid **alert fatigue** (too many noisy alerts → ignored). Use burn-rate alerts on the error budget.
- Have **runbooks** for each alert.

---

## 8. Interview Cheat Sheet

> **"How would you make this system observable?"**
> "Three pillars: **metrics** (RED — rate/errors/p99 latency, plus DLQ depth, queue lag), **structured logs** with correlation ids (never PII), and **distributed tracing** (OpenTelemetry, trace_id propagated across services) to localize latency. Then define **SLOs** (e.g. 99.9% success) with **error-budget** alerting, paging only on user-facing symptoms."

> **"Why percentiles not averages?"**
> "Averages hide tail latency; p99 shows the experience of your slowest 1% — often where the real problems are."

> **"Logs vs metrics vs traces?"**
> "Metrics detect that something's wrong (aggregate), traces localize where (across services), logs explain exactly what happened for a specific event."

> **"SLO vs SLA?"**
> "SLI is the measured signal; SLO is your internal target; SLA is the customer contract with penalties. Set SLOs stricter than SLAs; use the error budget to balance reliability vs shipping speed."

---

## 9. Final Takeaways

- **Three pillars:** metrics (detect, RED/USE, percentiles), structured logs (explain, correlation ids, no PII), traces (localize across services, OpenTelemetry).
- **SLI/SLO/SLA + error budget** = the reliability contract and the lever between reliability and velocity.
- **Alert on user-facing symptoms + budget burn**, not raw metrics; avoid alert fatigue; keep runbooks.
- Add this section to **every** system design: "metrics, logs, traces, alerts, SLOs."

### Related notes

- [Scaling Architecture](scaling-architecture.md) · [Apache Kafka](kafka.md) (consumer lag as a key SLI) · [Rate Limiting](rate-limiting.md)
