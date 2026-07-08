# Observability — Logs, Metrics, Traces & SLOs

> **Goal:** understand what a distributed system is doing and why — to detect, diagnose, and prevent problems. The three pillars are **logs, metrics, traces**; the operational contract is **SLI / SLO / SLA**. Interviewers expect you to add an "observability" section to any design.

> **How to read this doc:** each section has the dense summary first, then a **deep dive** (annotated examples and the exact confusions that come up while learning). Skim the summaries for revision; read the deep dives to actually understand.

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

### Monitoring vs observability

- **Monitoring** = the specific signals someone decided ahead of time to watch: error-rate alerts, a latency dashboard, a disk-space check. Each was predicted and wired up in advance. When one fires, you know *that* known thing happened.
- **Observability** = collecting rich enough raw data (metrics + logs + traces) that when something *unpredicted* happens, you can investigate and figure it out after the fact — even though nobody built a dedicated alert for it.

So monitoring answers **"is the thing I worried about happening?"** and observability answers **"something is off — what is going on?"** A system is *observable* if you can ask brand-new questions of it after the fact without having to ship new code first.

#### Q: Isn't this just a fancy word for monitoring?

They overlap, but the distinction is about **new questions**:

- Monitoring is a **subset** — the predefined dashboards and alerts.
- Observability is the **capability** — rich enough data (metrics + logs + traces with shared ids) that you can slice it in ways you didn't plan for. Example: "show me error rate **just** for `checkout` requests, **from** Android users, **in** the EU, **on** app version 4.2." If you never anticipated that exact breakdown but can still answer it from your telemetry, your system is observable.

#### Q: If I have good monitoring, do I still need observability?

Yes. Monitoring tells you when a **known** failure mode occurs. Observability is what lets you diagnose the **unknown** failure that no alert was set up for. Real incidents are usually the ones nobody predicted — that's exactly where observability earns its keep.

---

## 2. The Three Pillars

| Pillar | Answers | Example |
| --- | --- | --- |
| **Metrics** | "What / how much / how often?" (aggregate) | request rate, p99 latency, error % |
| **Logs** | "What exactly happened for this event?" | structured event with ids + error |
| **Traces** | "Where did the time/failure go across services?" | request path A→B→C with per-hop latency |

Together: metrics **detect**, traces **localize**, logs **explain**.

### The three pillars

Three different tools answer three different kinds of question:

- **Metrics** — always-on **numbers**: request rate, error rate, latency, CPU, queue depth, right now and over time. Cheap to store and aggregate, and great for spotting "something's off" (error rate is climbing). But a metric never tells you *why* or *what specifically* happened.
- **Logs** — a timestamped record for each notable event, with details: which user, which order, which error code. Each entry is **a specific thing that happened**. Perfect for "what exactly occurred at 11:20?" — but expensive for aggregate questions like "what's the average latency" (you'd have to scan huge numbers of entries).
- **Traces** — the full path of **one request** across services, hop by hop, **with how long each hop took**. This shows *where* the time went: "the DB hop took 300ms — that's the slow part."

Now map it to a software request:

- **Metric:** "requests per second = 4,000, error rate = 2%, p99 latency = 800ms." (aggregate numbers)
- **Log:** "request `req-98abc` for user 123 failed: `PaymentDeclined` at 11:20:03." (one specific event)
- **Trace:** "request `req-98abc` went gateway → order-svc → **db (slow, 300ms)** → payment." (the path + per-hop timing)

The punchline: **metrics tell you *that* something is wrong, traces tell you *where*, logs tell you *what exactly*.** You usually use them in that order during an incident.

#### Q: Why not just use logs for everything? Logs have all the detail.

Because logs are **expensive and slow to aggregate**. Answering "what's my p99 latency over the last 6 hours?" from logs means scanning millions of entries every time. A metric already stores that as a compact number time-series, so the answer is instant and cheap. Rule of thumb: use **metrics** for "how much / how often" (cheap, aggregate) and **logs** for "what happened in this one case" (detailed, per-event).

#### Q: When do I reach for each one in a real incident?

1. A **metric** alert fires ("error rate jumped to 5%") — *detect*.
2. You open a **trace** of a failing request to see which service/hop is broken or slow — *localize*.
3. You read the **logs** of that service around that time to see the exact error/stack/ids — *explain*.

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

### The three metric types

- **Counter** — a value that only ever goes **up** (monotonic): total requests served, total errors. You rarely care about its raw value, only *how fast it's climbing* — "requests_total went up by 500 this second = 500 req/s."
- **Gauge** — a **point-in-time** value that goes up **and** down: current memory used, queue depth, active connections right now.
- **Histogram** — a bucketed tally of how long things took: "how many requests fell in 0–10ms, 10–50ms, 50–100ms, 100ms+." From those buckets you compute **percentiles** (p50/p95/p99) — "99% of requests finished under 800ms."

#### Annotated example: a counter and a histogram

```java
// COUNTER — only goes up. Labels ("tags") let you slice it later.
Counter requests = Counter.builder("http_requests_total")
    .tag("service", "checkout")
    .tag("method", "POST")
    .register(registry);

requests.increment();   // +1 on every request; you NEVER decrement a counter

// HISTOGRAM — records the DURATION of each request into buckets.
Timer latency = Timer.builder("http_request_duration_seconds")
    .tag("service", "checkout")
    .publishPercentiles(0.5, 0.95, 0.99)   // p50 / p95 / p99
    .register(registry);

latency.record(() -> handleRequest());  // times the call, drops it in the right bucket
```

A dashboard then does the math for you: **rate** = how fast `http_requests_total` climbs, **error %** = `errors_total` rate ÷ `requests_total` rate, **p99** = read off the histogram.

#### RED and USE (which numbers to actually track)

Don't track random numbers — track these two well-known checklists:

- **RED** — for **services** (things handling requests): **R**ate (requests/sec), **E**rrors (how many failed), **D**uration (how long they took, as percentiles). "Is my service healthy from the caller's point of view?"
- **USE** — for **resources** (CPU, disk, memory, a queue): **U**tilization (how busy, e.g. 80% CPU), **S**aturation (how much work is *waiting* — queue length), **E**rrors. "Is this resource the bottleneck?"

RED describes the service from the caller's perspective (throughput, failures, latency); USE describes a resource's internal health (how busy, how backed up, failing).

#### Q: What is "high cardinality" and why does everyone warn about it?

**Cardinality = the number of distinct label/tag combinations**, and it's the #1 way people blow up their metrics bill.

Every unique combination of label values creates a **separate time-series** (its own stored line on the graph). Low-variety labels are fine; high-variety labels explode:

```java
// FINE — "method" has ~5 values, "status" ~10. 5 × 10 = 50 series. Cheap.
requests.tag("method", "POST").tag("status", "200");

// DISASTER — user_id has millions of values → millions of separate time-series.
requests.tag("user_id", "12345");     // BAD: NEVER put unbounded ids in a metric label
```

Rule: **only low-cardinality dimensions belong in metric labels** (method, region, status code, service name). High-cardinality things (user_id, request_id, email, exact URL with ids) belong in **logs**, not metric labels. (This is the exact same cardinality trap that shows up in stream aggregation keys — see the ad-click-aggregation note.)

#### Q: Why percentiles instead of a simple average?

Because averages **hide the pain of your slowest users**. Say 99 requests take 10ms and 1 request takes 5,000ms. The **average** is ~60ms — looks fine! But that 1 user waited 5 seconds. The **p99** (800ms+ in this case) exposes exactly that tail. Users remember the slow experiences, so p95/p99 reflect reality far better than the mean.

---

## 4. Logs

Timestamped records of events.

- **Structured logs (JSON)** > plain text — queryable by fields.
- Always include correlation ids: `request_id`, `trace_id`, `user_id`, `service`.
- **Levels**: DEBUG/INFO/WARN/ERROR; sample high-volume logs to control cost.
- **Never log secrets/PII** (tokens, card numbers, message bodies).
- Ship to a central store (ELK/OpenSearch, Loki, Splunk) — don't grep boxes.

### Structured logs and correlation ids

A log line records one event: *at this time, this specific thing happened.* The whole art of good logging is making those entries **searchable** and **safe**.

#### Annotated example: structured vs unstructured

```text
# UNSTRUCTURED (plain text) — human-ish, but a nightmare to query at scale:
2026-07-08 11:20:03 ERROR payment failed for user 123 order 998 declined
```

```json
// STRUCTURED (JSON) — every field is queryable. THIS is what you want.
{
  "timestamp": "2026-07-08T11:20:03Z",
  "level": "ERROR",
  "service": "payment-svc",
  "message": "payment declined",
  "trace_id": "abc123",      // ← ties this log to a distributed trace (§5)
  "request_id": "req-98abc", // ← ties together all logs for this one request
  "user_id": "123",
  "order_id": "998",
  "error_code": "CARD_DECLINED"
}
```

With structured logs you can run queries like `level:ERROR AND service:payment-svc AND error_code:CARD_DECLINED` across your whole fleet in seconds. With plain text you're stuck grepping and regexing.

**Why the correlation ids matter so much:** `trace_id` / `request_id` are the thread that stitches a single request back together across dozens of services and thousands of interleaved log lines. Without them, logs from different requests are hopelessly tangled — you can't tell which lines belong to which request.

#### Q: If I have metrics, why do I still need logs?

Metrics told you "error rate is 2%." Logs tell you **which** requests failed and **why** — the exact user, order, and `error_code: CARD_DECLINED`. Metrics = the count; logs = the story behind each one.

#### Q: Log levels — when do I use which?

- **DEBUG** — noisy developer detail; off in production normally.
- **INFO** — normal notable events ("order placed", "user logged in").
- **WARN** — something odd but handled ("retry #2", "cache miss, fell back to DB").
- **ERROR** — something actually failed and needs attention.

High-volume logs (like every INFO on a firehose endpoint) are often **sampled** — keep 1 in N — to control cost, while ERRORs are kept 100%.

#### Q: What counts as "don't log this"?

Never log **secrets or PII**: passwords, auth tokens, API keys, full card numbers, and message/request bodies that may contain personal data. Logs get shipped to a central store many people can search — a leaked token in a log is a real breach. Log an **id** (`user_id: 123`), never the sensitive value itself.

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

### A trace is the full path of one request

Your metrics said "checkout is slow." But checkout calls 6 other services — **which one** is the slow hop? That's exactly what a **trace** answers: it records **one single request** as it hops between services, timing every hop.

- A **trace** = the full path of one request (one `trace_id`).
- A **span** = one hop of that path (one operation in one service), with a start time, an end time, and tags.
- Spans nest into a **tree**: the gateway span contains the order-svc span, which contains the db-query span, etc.

```
trace_id = abc123                     ← one request's full path
  span: gateway        [0–2ms]        ← parent span
    span: order-svc    [2–40ms]
      span: db query   [5–30ms]   ← 25ms here = the slow hop! this is your culprit
      span: payment    [31–39ms]
```

Reading it: the total request took 40ms, and the "db query" hop ate 25ms of it — so you go optimize *that* query, not the payment call.

#### Annotated example: creating a span and propagating context

```java
// Start a span for THIS operation. It automatically joins the current trace.
Span span = tracer.spanBuilder("db.query.getOrder").startSpan();
try (Scope scope = span.makeCurrent()) {
    span.setAttribute("db.statement", "SELECT * FROM orders WHERE id = ?");
    span.setAttribute("order_id", "998");
    return runQuery();     // the time between startSpan() and end() = this span's duration
} catch (Exception e) {
    span.recordException(e);   // tag the span as failed so it shows red in the UI
    throw e;
} finally {
    span.end();            // stamps the end time
}
```

**Context propagation** is the secret sauce. When service A calls service B over HTTP, it passes the `trace_id` (and current span id) in a **header** so B's spans attach to the *same* trace instead of starting a new one:

```text
# The W3C "traceparent" header carried across every service hop:
traceparent: 00-abc123def456...-00f067aa0ba902b7-01
             │  └ trace_id ────┘ └ parent span id ┘
             version                               flags
```

Without propagation, each service would create its own disconnected trace and you'd lose the end-to-end picture — each hop would be recorded as a *separate* trace with no way to link them.

#### Q: What's the difference between a trace and a log?

A **log** is a single point-in-time entry ("payment declined at 11:20"). A **trace** is the **connected path with timing** across services for one request. Logs answer *what happened*; traces answer *where the time/failure went*. They link via the shared `trace_id`, so from a slow trace you can jump straight to the logs of the exact span that was slow.

#### Q: Why is tracing "sampled" (only ~1%)? Won't I miss things?

At millions of requests, storing a full trace for **every** request is hugely expensive and mostly redundant (the 999,999 healthy requests look identical). So you keep a **sample** — e.g. 1% random, often with "always keep traces that errored or were slow" (**tail-based sampling**). You still get a statistically representative view of latency, plus all the interesting (slow/failed) ones, while dropping most of the identical healthy ones.

---

## 6. SLI / SLO / SLA & Error Budgets

| Term | Definition | Example |
| --- | --- | --- |
| **SLI** (Indicator) | A measured signal | success rate, p99 latency |
| **SLO** (Objective) | Internal target for an SLI | 99.9% success over 30 days |
| **SLA** (Agreement) | Contract with penalties | 99.9% uptime or refund |
| **Error budget** | Allowed failure = 1 − SLO | 99.9% → ~43 min/month down |

> **Error budget** balances reliability vs velocity: if you're within budget, ship features; if you've burned it, freeze and fix reliability. SLA ⊇ SLO (SLA looser, with legal teeth); set SLOs stricter than SLAs.

### SLI vs SLO vs SLA (the confusing three)

These three look alike but sit at very different levels. Read them as a chain, **measurement → goal → promise**:

- **SLI = the measurement.** A number you actually collect. "99.95% of requests succeeded this week." (It comes straight from your metrics — see §3.) SLI = **I**ndicator = the *measured signal*.
- **SLO = your internal goal for that number.** "We want success ≥ 99.9% over 30 days." SLO = **O**bjective = the *target you set yourself*. No lawyers involved; it's your team's bar.
- **SLA = the promise to the customer, with penalties.** "If uptime drops below 99.9%, you get a refund." SLA = **A**greement = a *contract* with money/legal consequences attached.

Note you set your **SLO stricter** than the SLA, so there's a safety margin before the contractual (penalty-bearing) threshold is ever at risk.

> **Why SLO stricter than SLA?** If your public promise (SLA) is 99.9% and you *aim* internally at exactly 99.9%, any bad week breaks the contract. So you aim higher internally (e.g. SLO 99.95%) to keep a buffer before the SLA (and its penalties) is ever at risk. SLA ⊇ SLO — the SLA is the looser, legally-binding outer line.

#### Error budget = your allowance to fail

If your SLO is 99.9% success, then **0.1% is allowed to fail** — that 0.1% is your **error budget**. It's a concrete, spendable allowance:

```text
SLO           = 99.9% success over 30 days
Error budget  = 100% − 99.9% = 0.1%
30 days       ≈ 43,200 minutes
Budget        ≈ 0.1% × 43,200 min ≈ 43 minutes of "down"/failing per month
```

The clever part is what you *do* with it:

- **Budget remaining?** You can afford risk — ship features fast, do that risky migration.
- **Budget burned?** You've used up your allowed failure for the month → **freeze new features, fix reliability** until you're back in budget.

It turns "how much reliability work vs feature work?" from an argument into a **number**: a fixed monthly allowance you spend on risk, and once it's gone you stop taking risks until it resets.

#### Q: So which one do I get paged about?

You alert on **burning the error budget too fast** (see §7), derived from your **SLIs**, measured against your **SLO**. The **SLA** is mostly a business/legal artifact — you want your SLO alerts to fire *long before* you'd ever breach the customer-facing SLA.

---

## 7. Alerting

- Alert on **symptoms users feel** (error rate, latency, budget burn), not every low-level metric.
- Page on **actionable, urgent** issues; everything else → dashboards/tickets.
- Avoid **alert fatigue** (too many noisy alerts → ignored). Use burn-rate alerts on the error budget.
- Have **runbooks** for each alert.

### Alert on user-facing symptoms, not every metric

A system emits hundreds of low-level metrics. If **every** one could page you, you'd be woken for a single node at slightly-high CPU and you'd start ignoring all of them — including the one real outage. That's **alert fatigue**, and it's the number-one way alerting fails.

The fix: **page a human only for symptoms the user actually feels** — high error rate, high latency, the site being down, the error budget burning fast. Everything else (a single node at 75% CPU, a transient blip) goes to a **dashboard or a ticket**, not someone's phone at 3am.

#### Annotated example: a good alert rule (Prometheus-style)

```yaml
# GOOD — alerts on a USER-FACING SYMPTOM, sustained, at a meaningful level.
- alert: HighCheckoutErrorRate
  expr: |
    sum(rate(http_requests_total{service="checkout", status=~"5.."}[5m]))
      /
    sum(rate(http_requests_total{service="checkout"}[5m]))
    > 0.05                       # error rate above 5% ...
  for: 5m                        # ... sustained for 5 min (ignore momentary blips)
  labels:   { severity: page }   # wake someone up
  annotations:
    summary: "Checkout error rate >5% for 5m"
    runbook: "https://runbooks/checkout-errors"   # what to DO about it
```

```yaml
# BAD — alerts on a raw internal metric with no user impact. This is alert-fatigue fuel.
- alert: CpuAbove70
  expr: cpu_utilization > 0.70   # so what? 70% CPU may be perfectly healthy
```

The good rule has three things the bad one lacks: it's a **symptom** (errors users see), it's **sustained** (`for: 5m`, no flapping), and it links a **runbook** — the step-by-step "here's how to respond." Every page should be **actionable**: if there's nothing to do, it shouldn't page.

#### Burn-rate alerts (the modern way)

Instead of alerting on a raw threshold, alert on **how fast you're eating the error budget** (§6). Burning slowly? A ticket is fine. Burning so fast you'll exhaust the whole month's budget in an hour? **Page now.** This ties alerts directly to real user-impact and cuts noise — a small, brief error blip barely dents the budget and won't wake anyone.

#### Q: Why not just alert on everything to be safe?

Because alerts that don't matter **train people to ignore alerts**. A pager that cries wolf 50 times a day gets muted — and then the one real outage is missed. Fewer, meaningful, actionable alerts (each with a runbook) is far safer than a firehose of noise.

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
