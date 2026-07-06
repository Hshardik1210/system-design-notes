# Forward Proxy, Reverse Proxy & API Gateway

> **In one line:** **Forward proxy** protects the *client*, **reverse proxy** protects the *server*, **API gateway** = reverse proxy + brains 🧠.

---

## Contents

- [1. Big Picture (one line each)](#1-big-picture-one-line-each)
- [2. Forward Proxy](#2-forward-proxy)
- [3. Reverse Proxy](#3-reverse-proxy)
- [4. API Gateway](#4-api-gateway)
- [5. Key Differences](#5-key-differences)
- [6. How They Fit Together](#6-how-they-fit-together)
- [7. Interview Cheat Sheet](#7-interview-cheat-sheet)
- [8. Final Takeaways](#8-final-takeaways)

---

## 1. Big Picture (one line each)

| Component | Role |
| --- | --- |
| **Forward Proxy** | Acts on behalf of the **client** |
| **Reverse Proxy** | Acts on behalf of the **server** |
| **API Gateway** | A **smart reverse proxy** for APIs |

---

## 2. Forward Proxy

> Sits **between the client and the internet**.

```
Client → Forward Proxy → Internet (Server)
```

### Examples
- Corporate network proxy
- VPN
- Tools to access restricted websites

### Use cases
- Hide **client** identity
- Access control
- Caching
- Bypass geo restrictions

> **Mental model:** *"Client says: please fetch this for me."*

---

## 3. Reverse Proxy

> Sits **in front of the servers**.

```
Client → Reverse Proxy → Backend Servers
```

### Examples
- Nginx
- Load balancer

### Use cases
- Load balancing
- SSL termination
- Caching
- Security (hide the backend)

> **Mental model:** *"Server says: talk to me through this gateway."*

---

## 4. API Gateway

> A **specialized reverse proxy for APIs**.

```
Client → API Gateway → Microservices
```

### Examples
- AWS API Gateway
- Kong
- Zuul (Netflix)

### Extra features (beyond a plain reverse proxy)
- **Authentication** (JWT, OAuth)
- **Rate limiting**
- **Request routing**
- **Request transformation**
- **Aggregation** (combine multiple service calls into one response)

> **Mental model:** *"Smart entry point for all APIs."*

---

## 5. Key Differences

| Feature | Forward Proxy | Reverse Proxy | API Gateway |
| --- | --- | --- | --- |
| Sits for | Client | Server | Server |
| Hides | Client | Server | Server |
| Used by | Client | Server infra | Microservices |
| Main job | Control outgoing requests | Route incoming traffic | Manage APIs |
| Intelligence | Low | Medium | High |

### Real-world analogy

| Component | Analogy |
| --- | --- |
| **Forward Proxy** | You ask a friend to buy something *for you* |
| **Reverse Proxy** | A reception desk directing visitors to the right room |
| **API Gateway** | A smart receptionist who checks ID, decides where to send you, combines requests, and limits access |

---

## 6. How They Fit Together

```
                🌐 Internet
                     |
             ┌────────────────┐
             │ Forward Proxy  │  (client side - optional)
             └────────────────┘
                     |
                📱 Client App
                     |
             ┌────────────────┐
             │  API Gateway   │  (auth, rate limit, routing)
             └────────────────┘
                     |
             ┌────────────────┐
             │ Reverse Proxy  │  (load balancer - Nginx)
             └────────────────┘
              /        |        \
   ┌────────────┐ ┌────────────┐ ┌────────────┐
   │ User Svc   │ │ Order Svc  │ │ Payment Svc│
   └────────────┘ └────────────┘ └────────────┘
         |               |               |
      ┌─────┐         ┌─────┐         ┌─────┐
      │ DB  │         │ DB  │         │ DB  │
      └─────┘         └─────┘         └─────┘
```

### Request flow

1. **Client → Forward Proxy (optional)** — corporate networks / VPNs; hides client IP, applies restrictions. *Usually absent for normal mobile apps.*
2. **API Gateway (main entry point)** — authentication (JWT), rate limiting, routing decision:
   ```
   /api/orders   → Order Service
   /api/payments → Payment Service
   ```
3. **Reverse Proxy (load balancer)** — distributes traffic across instances; SSL termination; failover.
4. **Backend services → databases** — business logic + DB operations.

> ⚠️ **Important nuance:** an API Gateway is basically a **supercharged reverse proxy**. Many systems skip a separate reverse proxy:
> ```
> Client → API Gateway → Services
> ```

> **Real-world (Netflix-like):** `User → ISP Proxy → API Gateway → Nginx → Microservices`

---

## 7. Interview Cheat Sheet

> **"Difference between forward proxy, reverse proxy, API gateway?"**
>
> "A **forward proxy** sits between the client and the internet and acts on behalf of the **client**. A **reverse proxy** sits in front of servers and routes client requests to backend services. An **API gateway** is a specialized reverse proxy that adds authentication, rate limiting, and request routing for microservices."

> **"Show an architecture using all three."**
>
> "The client may optionally use a forward proxy. Requests go to the **API Gateway** (auth, rate limiting, routing), which forwards to a **reverse proxy / load balancer** that distributes traffic across backend service instances."

---

## 8. Final Takeaways

```
Forward Proxy → protects the client (outside your system)
Reverse Proxy → protects the server (internal load balancer)
API Gateway   → manages APIs (entry point, decision maker)
```

- **API Gateway = Reverse Proxy + brains** 🧠 (often replaces a separate reverse proxy).
