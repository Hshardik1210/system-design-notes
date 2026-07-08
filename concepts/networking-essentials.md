# Networking Essentials

> The network fundamentals that show up in system-design interviews: **DNS**, **TCP vs UDP**, **HTTP versions**, **TLS/HTTPS**, and where each matters. You rarely design these, but you must know how a request travels and the trade-offs.

> **How to read this doc:** each section has the dense summary first, then a **deep dive** (annotated examples and the exact confusions that trip beginners up). Skim the summaries for revision; read the deep dives to actually understand.

---

## Contents

- [1. What Happens When You Type a URL](#1-what-happens-when-you-type-a-url)
- [2. DNS](#2-dns)
- [3. TCP vs UDP](#3-tcp-vs-udp)
- [4. HTTP Versions (1.1 / 2 / 3)](#4-http-versions-11--2--3)
- [5. TLS / HTTPS](#5-tls--https)
- [6. Interview Cheat Sheet](#6-interview-cheat-sheet)
- [7. Final Takeaways](#7-final-takeaways)

---

## 1. What Happens When You Type a URL

```
1. DNS resolves domain → IP (browser/OS cache → resolver → root → TLD → authoritative)
2. TCP handshake (SYN/SYN-ACK/ACK) to the server IP:443
3. TLS handshake (negotiate keys, verify cert)
4. HTTP request sent; server responds
5. (often via) CDN / Load Balancer / reverse proxy in front of app servers
6. Browser renders; keeps connection alive (keep-alive) for more requests
```

### The steps of loading a URL

Visiting `example.com` involves several distinct steps, each solving a different problem:

1. **DNS = resolving the name to an address.** You have the *name* (`example.com`), but the network needs an *IP address* (like `93.184.216.34`). DNS translates the name into the address.
2. **TCP handshake = establishing the connection.** Before sending data, both sides confirm they can send and receive (SYN / SYN-ACK / ACK).
3. **TLS handshake = authentication + encryption setup.** The client verifies the server's identity (its certificate) and both sides derive a shared secret key so the traffic can't be read in transit.
4. **HTTP request = the actual request/response.** "Send me your home page." The server replies with the page (the HTTP response).
5. **CDN / load balancer = intermediaries in front of the origin.** Often the request doesn't reach the origin directly — a nearby CDN edge, or a load balancer that forwards to a healthy backend, handles it first.
6. **Keep-alive = reusing the connection.** Rather than open a new connection per request, the connection stays open to fetch more (images, scripts, next page).

Everything below is just each of those steps in detail.

#### Q: What's the difference between an IP address and a port?

- **IP address** identifies *which machine* on the internet (e.g. `93.184.216.34`).
- **Port** identifies *which program/service* on that machine (e.g. `443` for HTTPS, `80` for plain HTTP, `22` for SSH).

So `93.184.216.34:443` means "the HTTPS service, on that server." One machine runs many services, each listening on its own port.

#### Q: Why so many steps just to load one page?

Each step solves a different worry: DNS answers *where*, TCP answers *are we reliably connected*, TLS answers *is it private and is the server genuine*, and HTTP is the *actual conversation*. They're layered so each layer can be reused and swapped independently (e.g. HTTP/3 swaps TCP for QUIC without changing the rest).

---

## 2. DNS

Translates human names (`example.com`) → IP addresses.

```
Browser cache → OS cache → Resolver (ISP) → Root → TLD (.com) → Authoritative NS → IP
                                (cached at each level with TTL)
```

| Record | Purpose |
| --- | --- |
| **A / AAAA** | Domain → IPv4 / IPv6 |
| **CNAME** | Alias → another domain |
| **MX** | Mail servers |
| **NS** | Authoritative name servers |
| **TXT** | Verification/SPF/DKIM |

- **TTL** controls caching (low TTL = faster failover, more lookups).
- **GeoDNS** returns different IPs by user location (route to nearest region).
- **Anycast** — same IP announced from many locations; routed to the nearest (CDNs, DNS).

### How DNS resolution works

Computers don't connect to `google.com`; they connect to IP addresses like `142.250.183.14`. **DNS (Domain Name System) is the distributed system** that translates the human-readable name into the IP address.

A lookup walks a chain, checking caches first and only going further when needed:

1. **Browser cache** — if the name was resolved recently, answer instantly.
2. **OS cache** — the operating system's cached entries, still fast.
3. **Resolver** (your ISP's) — does the recursive lookup work when the caches miss.
4. The resolver queries up the hierarchy: **Root** (which server handles `.com`?) → **TLD** (the `.com` registry: who's authoritative for `example.com`?) → **Authoritative name server** (which actually holds example.com's IP).
5. The answer flows back and **each level caches it for a while** so the next lookup is instant. How long it's cached = the **TTL** (time-to-live).

```
You type example.com
  → Browser cache?    (have I looked this up recently?)   → if yes, done
  → OS cache?                                              → if yes, done
  → Resolver (ISP): "I'll find out"
        → Root server:     "for .com, ask the .com registry"
        → .com TLD server: "for example.com, ask ns1.example.com"
        → Authoritative:   "example.com = 93.184.216.34"   ← the real answer
  → answer cached at each level (for TTL seconds), returned to your browser
```

#### Annotated example: a DNS lookup on the command line

```bash
$ dig example.com

;; QUESTION SECTION:
example.com.            IN  A                # "What is the A record (IPv4) for example.com?"

;; ANSWER SECTION:
example.com.    3600    IN  A   93.184.216.34
#               ^^^^                 ^^^^^^^^^^^^
#               TTL: cache this      the IP your browser will connect to
#               for 3600s (1 hour)
```

The `3600` is the TTL: for the next hour, resolvers reuse this answer instead of asking again.

#### Q: What are the record types (A, CNAME, MX...) really for?

Each DNS record is a labelled entry answering a *different* question about the domain:

- **A / AAAA** — "what's the IP?" (A = IPv4, AAAA = IPv6). The most common lookup.
- **CNAME** — "this name is an alias for *that* name" (e.g. `www.example.com` really points to `example.com`).
- **MX** — "which servers receive *email* for this domain?"
- **NS** — "which name servers are the authority for this domain?"
- **TXT** — free-form text, used for verification/anti-spam (SPF, DKIM proving who may send mail as you).

#### Q: Why does TTL matter? Low vs high?

TTL is "how long may everyone cache this answer before re-checking."

- **High TTL** (e.g. 24h): fewer lookups, faster, less load — but if you change servers, the old IP lingers in caches for up to a day.
- **Low TTL** (e.g. 60s): changes propagate almost immediately (great before a planned migration or for failover) — but every cache expires quickly, so more lookups. Teams often *lower* TTL a day before a big DNS change, then raise it again.

#### Q: GeoDNS and anycast — how do they route me to the nearest server?

- **GeoDNS**: the authoritative server looks at *where the question came from* and hands back a *different IP* — a user in India gets the Mumbai IP, a user in the US gets the Virginia IP. Same name, location-aware answer.
- **Anycast**: many servers around the world announce the *same* IP, and internet routing naturally delivers you to the closest one. Used heavily by CDNs and public DNS (like `8.8.8.8`).

---

## 3. TCP vs UDP

| | **TCP** | **UDP** |
| --- | --- | --- |
| Connection | Connection-oriented (handshake) | Connectionless |
| Reliability | **Reliable, ordered**, retransmits | Best-effort, may drop/reorder |
| Overhead | Higher (ACKs, congestion control) | Low, fast |
| Use | Web, APIs, DB, most everything | Video/voice, gaming, DNS, QUIC |

> **Rule:** TCP when you can't lose data (most apps); UDP when **low latency > perfect delivery** (live streams, games, VoIP). HTTP/3 runs over **QUIC (UDP)** but adds reliability itself.

### TCP vs UDP in detail

Both TCP and UDP carry your data as **packets** (chunks) across the network. They differ in *what guarantees they provide*.

- **TCP is connection-oriented and reliable.** It first establishes a connection (the handshake), then delivers an ordered, reliable stream: lost packets are retransmitted and everything arrives *in order*. More overhead, but nothing is lost or reordered.
- **UDP is connectionless and best-effort.** It just sends packets with no setup, no acknowledgement, and no retransmission. Faster and simpler, but packets may be lost or arrive out of order — and the sender isn't notified.

#### The TCP handshake

Before any data, TCP does a **three-way handshake** so both sides agree they can send and receive:

```
Client                                Server
  │  ── SYN ───────────────────────►   │   open request + client's initial sequence number
  │  ◄────────────── SYN-ACK ───────   │   acknowledges client, sends server's sequence number
  │  ── ACK ───────────────────────►   │   acknowledges server; connection established
  │                                     │
  │  ==== now data flows both ways, reliably & in order ====
```

- **SYN** = "synchronize" (open the connection, here's my sequence number).
- **ACK** = "acknowledge" (I received yours).
- After this, every chunk is numbered; the receiver ACKs what it got, and anything missing is resent. That's what "reliable, ordered" means — and why TCP has more overhead than UDP.

#### Q: TCP vs UDP — when do I pick which?

Ask: *"Is a lost or out-of-order piece a disaster, or just a tiny glitch?"*

- **Use TCP when losing data is unacceptable** — web pages, APIs, databases, file downloads, payments. A missing byte would corrupt the result, so you want retransmission and ordering. This is the default for almost everything.
- **Use UDP when being *late* is worse than losing a little** — live video/voice calls, online games, live streams. If one video frame is lost, you'd rather skip it than freeze the whole call waiting for a resend. A slightly glitchy live call beats a perfectly complete but delayed one.

#### Q: If UDP is unreliable, why do DNS and HTTP/3 use it?

- **DNS** queries are tiny (one small question, one small answer). It's cheaper to fire a quick UDP packet and just re-ask if no reply comes than to set up a whole TCP connection for one lookup. (DNS falls back to TCP for large responses.)
- **HTTP/3** uses **QUIC**, which runs *on top of* UDP but **re-adds reliability and ordering itself** in a smarter way than TCP. So it gets UDP's flexibility (no rigid TCP handshake, no TCP head-of-line blocking) while still not losing data. UDP here is a lightweight foundation to build on, not "unreliable by choice."

---

## 4. HTTP Versions (1.1 / 2 / 3)

| Version | Transport | Key feature | Problem it fixes |
| --- | --- | --- | --- |
| **HTTP/1.1** | TCP | Keep-alive, one request at a time per conn | Head-of-line blocking; needs many connections |
| **HTTP/2** | TCP | **Multiplexing** many streams on one conn, header compression, server push | 1.1's connection overhead — but **TCP-level** HOL blocking remains |
| **HTTP/3** | **QUIC (UDP)** | Multiplexing without TCP HOL blocking, faster handshake, connection migration | HTTP/2's TCP HOL blocking; faster on lossy/mobile networks |

- **HTTP/2 multiplexing:** many concurrent requests over one TCP connection (no more 6-connection limit).
- **HTTP/3/QUIC:** independent streams so one lost packet doesn't stall the others; 0-RTT resumption.

### What differs between HTTP versions

**HTTP is the request/response protocol** the browser and server speak: "GET `/index.html`" → "here it is, 200 OK." All three versions use the same request/response model — they differ in **how efficiently they move the messages** over the wire.

#### Annotated example: what one HTTP request/response looks like

```http
GET /index.html HTTP/1.1        # method (GET) + path + version
Host: example.com               # which site (one IP can host many)
User-Agent: Chrome/120          # who's asking
Accept: text/html               # what I can handle back
                                # blank line = end of request

HTTP/1.1 200 OK                 # status: 200 = success
Content-Type: text/html         # what's coming back
Content-Length: 1256            # how many bytes
                                # blank line, then the actual page:
<html>...</html>
```

That shape (method, headers, body) is the same across versions — the plumbing underneath changes.

The progression is about concurrency and head-of-line blocking:

- **HTTP/1.1** — one request at a time per connection; the next waits until the first finishes. Browsers worked around this by opening ~6 parallel connections per site. The problem: one slow request blocks the ones behind it on that connection (**head-of-line blocking**).
- **HTTP/2** — **multiplexing**: many requests share *one* connection at the same time, interleaved, plus compressed headers. Much less overhead. But because it still rides on **TCP**, if one packet is lost, *TCP itself* stalls *all* streams until it's retransmitted — so a subtler head-of-line blocking remains at the transport layer.
- **HTTP/3** — built on **QUIC (UDP)**. Streams are truly independent: a lost packet only pauses *its own* stream, not the others. Plus faster setup (fewer round trips, even 0-RTT to resume a known server) — a big win on lossy mobile/Wi-Fi networks. It even survives a network switch (Wi-Fi → cellular) without dropping the connection (**connection migration**).

#### Q: What is "head-of-line blocking" in plain terms?

It's when **one stuck item blocks everything queued behind it**.

- **HTTP/1.1**: one slow *request* blocks the next on that connection (application level).
- **HTTP/2**: fixes that at the app level (many streams), but one lost *packet* on the shared TCP connection freezes all streams (transport level).
- **HTTP/3/QUIC**: streams are independent, so a lost packet only affects its own stream — the blocking is gone.

#### Q: Is HTTP/3 always better? Should I always use it?

It's usually better on lossy/mobile networks and for latency, and modern browsers/CDNs negotiate it automatically (falling back to HTTP/2 or 1.1 if needed). But it's not a hard rule: some networks/firewalls handle UDP poorly, and on a clean, stable connection the difference is small. In interviews the key point is the *reasoning* — HTTP/2 removed connection overhead, HTTP/3 removed TCP's head-of-line blocking.

---

## 5. TLS / HTTPS

HTTPS = HTTP over **TLS** (encryption + integrity + authentication).

```
TLS handshake (simplified):
  1. Client hello (supported ciphers)
  2. Server sends certificate (public key, signed by a CA)
  3. Client verifies cert chain against trusted CAs
  4. Key exchange (e.g. ECDHE) → shared symmetric session key
  5. Encrypted communication with the fast symmetric key
```

| Concept | Note |
| --- | --- |
| **Asymmetric → symmetric** | Slow public-key crypto only to exchange a fast symmetric key |
| **Certificate / CA** | Proves server identity; chain of trust to a root CA |
| **TLS termination** | Often at the LB/CDN (decrypt there), then internal traffic (mTLS or plaintext in VPC) |
| **mTLS** | Both sides present certs — service-to-service auth |
| **1-RTT / 0-RTT** | TLS 1.3 speeds up the handshake |

### What TLS provides

Plain HTTP is sent in the clear — anyone on the network path (your ISP, shared Wi-Fi, an attacker) can read and modify it. **HTTPS wraps HTTP in TLS**, which adds three guarantees:

- **Confidentiality** (encryption) — only the two endpoints can read the traffic,
- **Integrity** — tampering in transit is detected,
- **Authentication** — the client verifies the server really is who it claims (not an impostor).

TLS achieves the last one by having the server present a certificate: the client (1) checks the certificate, (2) confirms it was issued by a CA it trusts, then (3) both sides derive a shared secret key for the rest of the session.

#### The TLS handshake, step by step

```
Client                                                 Server
  │  ── Client Hello ──────────────────────────────►    │  cipher suites the client
  │      (cipher suites I support, random number)        │  supports + a random number
  │                                                       │
  │  ◄──────── Server Hello + Certificate ──────────     │  chosen cipher + certificate
  │      (chosen cipher, cert with public key,            │  (public key), signed by a CA,
  │       server random number)                          │  + a random number
  │                                                       │
  │  [Client verifies the certificate:                    │
  │     - is it signed by a CA my system trusts?          │
  │     - is it for THIS domain, not expired, not revoked?]
  │                                                       │
  │  ── Key exchange (e.g. ECDHE) ──────────────────►    │  both sides derive the SAME
  │  ◄────────────────────────────────────────────      │  secret symmetric session key
  │                                                       │
  │  ==== all further traffic encrypted with the fast ====
  │  ==== symmetric session key (the real HTTP) ==========
```

#### Q: Why two kinds of encryption (asymmetric *and* symmetric)?

Two tools, each for what it's best at:

- **Asymmetric (public/private key)** can **establish a shared secret between two parties that have never met** and verify identity — but it's *slow*. So TLS uses it only briefly, during the handshake.
- **Symmetric (one shared key both sides use)** is **fast** — but requires both sides to already share the secret. Once the handshake has established that shared key, all the *actual data* uses fast symmetric encryption.

So asymmetric crypto is used *once* to securely agree on a symmetric key; then the fast symmetric key encrypts everything after.

#### Q: What's a certificate and a CA, and why do I trust them?

- A **certificate** is the server's identity document: "I am example.com, here's my public key," **digitally signed by a Certificate Authority (CA)**.
- A **CA** (like Let's Encrypt, DigiCert) is a trusted third party. Your operating system/browser ships with a built-in list of CAs it trusts. If a cert is signed by one of them (and matches the domain, isn't expired, isn't revoked), you trust it — the **chain of trust** back to a **root CA**.
- This stops an attacker from impersonating a site: they can't produce a valid CA-signed certificate for a domain they don't control.

#### Q: What is TLS termination and mTLS?

- **TLS termination** = the point where HTTPS is *decrypted*. Often this happens at the **load balancer or CDN** at the edge: it decrypts once, then forwards plain (or re-encrypted) traffic to internal app servers within the trusted private network. This offloads crypto work from app servers.
- **mTLS (mutual TLS)** = *both* sides present certificates, not just the server. Normal HTTPS only verifies the server; mTLS also verifies the *client*. Used for **service-to-service authentication** inside a system, where each service proves its identity to the others.

#### Q: What do 1-RTT and 0-RTT mean?

**RTT = round trip time** (one message out and back). Older TLS needed several round trips to handshake before any data — noticeable latency. **TLS 1.3** cuts this to **1-RTT** (one round trip), and for a server you've talked to before, **0-RTT** lets you send data on the very first message. Fewer round trips = faster page loads, especially on high-latency mobile networks.

---

## 6. Interview Cheat Sheet

> **"Walk me through what happens when I load a URL."**
> "DNS resolves the domain to an IP (cached at multiple levels), TCP handshake to the server, TLS handshake to establish an encrypted session, then the HTTP request — usually through a CDN/LB/reverse proxy to app servers — and the response renders."

> **"TCP or UDP?"**
> "TCP for reliable, ordered delivery (most apps); UDP for low-latency loss-tolerant traffic (video, games, DNS). HTTP/3 uses QUIC over UDP but re-adds reliability."

> **"Why HTTP/2 or HTTP/3?"**
> "HTTP/2 multiplexes many requests over one connection (no connection-count limit) with header compression. HTTP/3 (QUIC/UDP) removes TCP head-of-line blocking and has faster handshakes — better on lossy/mobile networks."

> **"How does HTTPS work?"**
> "TLS handshake: verify the server's CA-signed certificate, do an asymmetric key exchange to derive a shared symmetric key, then encrypt with the fast symmetric key. Often terminated at the LB/CDN; mTLS for service-to-service auth."

---

## 7. Final Takeaways

- **DNS** name→IP, cached with TTL; GeoDNS/anycast route to nearest.
- **TCP** = reliable/ordered (default); **UDP** = fast/lossy (media, games, QUIC).
- **HTTP/2** = multiplexing over one TCP conn; **HTTP/3** = QUIC/UDP, no TCP HOL blocking.
- **TLS** = verify cert (CA chain) → key exchange → symmetric encryption; terminate at LB/CDN; **mTLS** for internal auth.

### Related notes

- [Load Balancing](load-balancing.md) · [Proxies & API Gateway](proxies-and-api-gateway.md) · [API Paradigms](api-paradigms.md)
