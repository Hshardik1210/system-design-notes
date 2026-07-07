# Networking Essentials

> The network fundamentals that show up in system-design interviews: **DNS**, **TCP vs UDP**, **HTTP versions**, **TLS/HTTPS**, and where each matters. You rarely design these, but you must know how a request travels and the trade-offs.

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

---

## 3. TCP vs UDP

| | **TCP** | **UDP** |
| --- | --- | --- |
| Connection | Connection-oriented (handshake) | Connectionless |
| Reliability | **Reliable, ordered**, retransmits | Best-effort, may drop/reorder |
| Overhead | Higher (ACKs, congestion control) | Low, fast |
| Use | Web, APIs, DB, most everything | Video/voice, gaming, DNS, QUIC |

> **Rule:** TCP when you can't lose data (most apps); UDP when **low latency > perfect delivery** (live streams, games, VoIP). HTTP/3 runs over **QUIC (UDP)** but adds reliability itself.

---

## 4. HTTP Versions (1.1 / 2 / 3)

| Version | Transport | Key feature | Problem it fixes |
| --- | --- | --- | --- |
| **HTTP/1.1** | TCP | Keep-alive, one request at a time per conn | Head-of-line blocking; needs many connections |
| **HTTP/2** | TCP | **Multiplexing** many streams on one conn, header compression, server push | 1.1's connection overhead — but **TCP-level** HOL blocking remains |
| **HTTP/3** | **QUIC (UDP)** | Multiplexing without TCP HOL blocking, faster handshake, connection migration | HTTP/2's TCP HOL blocking; faster on lossy/mobile networks |

- **HTTP/2 multiplexing:** many concurrent requests over one TCP connection (no more 6-connection limit).
- **HTTP/3/QUIC:** independent streams so one lost packet doesn't stall the others; 0-RTT resumption.

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
