# Authentication & Sessions (Login, JWT, Refresh Tokens)

> **The core question:** after a user logs in, *"how do we remember this user on future requests?"* Two answers: **sessions** (server remembers you) and **JWT** (you carry your signed identity).

---

## Contents

- [1. The Problem](#1-the-problem)
- [2. Session-Based Authentication (stateful, classic)](#2-session-based-authentication-stateful-classic)
- [3. Token-Based Authentication (JWT, stateless)](#3-token-based-authentication-jwt-stateless)
- [4. Session vs JWT](#4-session-vs-jwt)
- [5. JWT Anatomy — `header.payload.signature`](#5-jwt-anatomy--headerpayloadsignature)
- [6. JWT Validation (step by step)](#6-jwt-validation-step-by-step)
- [7. Session Validation (step by step)](#7-session-validation-step-by-step)
- [8. Access Token + Refresh Token](#8-access-token--refresh-token)
- [9. Who Generates the Refresh Token?](#9-who-generates-the-refresh-token)
- [10. Logout in JWT (the tricky part)](#10-logout-in-jwt-the-tricky-part)
- [11. Why "just check the refresh token on each request" Doesn't Work](#11-why-just-check-the-refresh-token-on-each-request-doesnt-work)
- [12. Refresh Token Security (it's like a password)](#12-refresh-token-security-its-like-a-password)
- [13. Multiple Devices / Sessions](#13-multiple-devices--sessions)
- [14. DB Schema (sessions / refresh tokens)](#14-db-schema-sessions--refresh-tokens)
- [15. Sample Code (Node.js / Express)](#15-sample-code-nodejs--express)
- [16. Auth System Design (API Gateway + Redis + JWT)](#16-auth-system-design-api-gateway--redis--jwt)
- [17. OAuth2, OIDC & SSO ("Login with Google")](#17-oauth2-oidc--sso-login-with-google)
- [18. Interview Cheat Sheet](#18-interview-cheat-sheet)
- [19. Final Takeaways](#19-final-takeaways)

---

## 1. The Problem

HTTP is **stateless** — each request is independent. After login, the server needs a way to recognize the same user on the next request without re-asking for the password.

```
Session approach: server stores who you are → checks on every request (stateful)
JWT approach:     server gives you a signed token → you present it each time (stateless)
```

---

## 2. Session-Based Authentication (stateful, classic)

### Flow

```
1. POST /login { email, password }
2. Server verifies credentials against DB
3. Server creates a session:
       session_id = random_string()
       store in Redis/DB:  session_id → user_id
4. Set-Cookie: session_id=abc123; HttpOnly
5. Next request:  Cookie: session_id=abc123
6. Server looks up session_id → user_id  → authenticated ✅
```

- **Session store:** Redis (fast) or DB (less common at scale).
- ❌ **Downsides:** needs server storage; **stateful** → harder to scale; every request does a store lookup.

> **Mental model:** *"Server remembers you."*

### Verifying credentials (applies to sessions *and* JWT)

> Step 2 ("verify credentials") is the same for both approaches and deserves real care:

- **Never store plaintext passwords.** Store a **salted hash** using a slow, memory-hard algorithm: **bcrypt / scrypt / Argon2** (not raw SHA-256, which is too fast to brute-force).
- Login = `hash(input_password, stored_salt) == stored_hash` (constant-time compare).
- Add **rate limiting / lockout** on the login endpoint to slow brute-force, and ideally **MFA** for sensitive systems.

---

## 3. Token-Based Authentication (JWT, stateless)

### Flow

```
1. POST /login { email, password }
2. Server verifies credentials
3. Server generates a signed JWT:
       JWT = encode({ user_id: 123, exp }) signed with SECRET
4. Return token → client stores it
5. Next request:  Authorization: Bearer <JWT>
6. Server verifies signature + expiry, extracts user_id → NO DB lookup ✅
```

> **Mental model:** *"You carry your identity."* Modern systems prefer JWT because it's **stateless and scalable**.

---

## 4. Session vs JWT

| Feature | Session | JWT |
| --- | --- | --- |
| Storage | Server (Redis/DB) | Client |
| DB lookup per request | Required | Not required |
| State | Stateful | Stateless |
| Scalability | Harder | Easier |
| Revocation / logout | Easy (delete session) | Hard (token valid until expiry) |
| Speed | Slight overhead (lookup) | Faster (verify signature) |
| Typical users | Banking / high-security | Netflix / Amazon / scale APIs |

> **One-liner:** Session = *check with the server*. JWT = *trust the (signed) token*.

---

## 5. JWT Anatomy — `header.payload.signature`

A JWT is just a string with **3 base64 parts** separated by dots:

```
eyJhbGciOiJIUzI1NiJ9 . eyJ1c2VyX2lkIjoxMjN9 . ABCxyz...
   header                  payload               signature
```

| Part | Contains |
| --- | --- |
| **Header** | algorithm + type, e.g. `{ "alg": "HS256", "typ": "JWT" }` |
| **Payload** | claims: `{ "user_id": 123, "role": "user", "exp": ... }` |
| **Signature** | proof the token is authentic (the important part) |

### Who creates the signature? → **The server** (never the client)

```
signature = HMAC_SHA256(
    base64(header) + "." + base64(payload),
    SECRET_KEY
)
```

On each request the server **recomputes** the signature and compares:

- match → token is authentic, untampered ✅
- mismatch → tampered → reject ❌

> ⚠️ **The payload is only base64-encoded, not encrypted** — anyone can read it. Never put secrets/passwords in a JWT. The signature guarantees *integrity*, not *confidentiality*.

> **Key rule:** the client **never** creates or modifies the signature; only the server (which holds the SECRET) can.

### Standard (registered) claims

> Beyond `user_id`/`role`, JWTs have well-known short claim names. Knowing these signals depth:

| Claim | Meaning |
| --- | --- |
| `sub` | **subject** — who the token is about (e.g. user id) |
| `iss` | **issuer** — who minted it (your auth server) |
| `aud` | **audience** — who it's intended for (which API/service) |
| `exp` | **expiry** (unix time) — reject after this |
| `iat` | **issued at** |
| `nbf` | **not before** — token invalid until this time |
| `jti` | **JWT ID** — unique id; useful for **blacklisting a single token** |

> A strict validator checks `exp`, `nbf`, `iss`, and `aud` — not just the signature. Allow a small **clock-skew** tolerance (e.g. ±30s) when comparing times across servers.

### Symmetric (HS256) vs Asymmetric (RS256/ES256)

> **The most important "depth" point for microservices.**

| | Symmetric — **HS256** | Asymmetric — **RS256 / ES256** |
| --- | --- | --- |
| Keys | one **shared secret** signs *and* verifies | **private key** signs, **public key** verifies |
| Who can verify | anyone with the secret (= anyone who can also forge) | anyone with the **public** key (can verify, **can't forge**) |
| Best for | a single service that signs + verifies | **many services / a gateway** verifying tokens an auth server signed |
| Risk | secret leaks → attacker mints valid tokens | private key stays only on the auth server |

```
HS256:  verify(token, SHARED_SECRET)         # every verifier also holds forging power
RS256:  verify(token, PUBLIC_KEY)            # verifiers can check but never mint
        sign(payload, PRIVATE_KEY)           # only the auth server signs
```

> **Why it matters:** in a microservices setup you don't want to hand the signing secret to every service. With **RS256**, the auth service holds the private key; the gateway/services hold only the **public key** (often fetched from a **JWKS** endpoint, `/.well-known/jwks.json`). This is also what lets third parties (e.g. Google) verify tokens they didn't issue.

---

## 6. JWT Validation (step by step)

> Done at the **API Gateway** or backend service. No DB call needed.

```
1. Extract token from  Authorization: Bearer <JWT>
2. Verify signature using SECRET   → ensures not tampered + issued by us
3. Check expiry  (exp > now?)      → reject if expired
4. Decode payload → user_id
5. Attach user to request (request.user = 123)
6. Continue → handle the request
```

> **Why JWT scales:** steps 2–4 are pure computation — no database/Redis lookup.

---

## 7. Session Validation (step by step)

```
1. Extract session_id from cookie
2. Look up session_id in Redis  → user_id
3. Validate: exists? not expired?
4. Attach user to request
5. Continue
```

> Difference vs JWT: **a store lookup** instead of a signature check → stateful, but **easy to revoke** (just delete the session).

---

## 8. Access Token + Refresh Token

> **Why two tokens?** A JWT can't be revoked, so you don't want it long-lived. Split responsibilities:

| Token | Lifetime | Used for | Stored |
| --- | --- | --- | --- |
| **Access token** (JWT) | short (10–15 min) | every API call | client |
| **Refresh token** | long (7–30 days) | only `/refresh` to get a new access token | **server** (DB/Redis) + client |

> **Analogy:** Access token = a temporary **entry pass**; refresh token = your **ID card** that gets you a new pass.

### Full flow

```
1. Login → server returns BOTH: access (15m) + refresh (7d)
2. Normal API calls use the access token
3. Access token expires (15m)
4. Client → POST /refresh { refreshToken }
5. Server checks refresh token in DB → if valid, issues a NEW access token
6. Logout → server DELETES the refresh token → user can't get new access tokens
```

### Why short access + long refresh?

- Long-lived access token → if **stolen**, attacker has access for the whole lifetime ❌.
- Short access + controllable refresh → **security + usability balance** ✅.

---

## 9. Who Generates the Refresh Token?

> **The backend generates BOTH tokens.** The client only **stores and sends them back**.

```
Login:
  access_token  = sign({ user_id, exp }, SECRET)   # JWT
  refresh_token = random_string()                  # e.g. a UUID
  store in DB/Redis:  refresh_token → { user_id, device, expiry }
  return both to client
```

**Why server-generated + stored server-side?** Because the server must **control** it — that's how logout / session revocation works. If the client could mint refresh tokens, there'd be no security and no way to revoke.

---

## 10. Logout in JWT (the tricky part)

> ❗ JWT is **stateless** — the server doesn't store it, so it **can't simply "delete" an access token**. After logout the old JWT stays valid **until it expires**.

### Solutions

| Option | How | Trade-off |
| --- | --- | --- |
| **1. Short-lived access tokens** (most common) | 10–15 min expiry; client discards token; delete refresh token | tiny window where old token still works; stays stateless |
| **2. Blacklist (Redis)** | on logout, store the token in a Redis blacklist (with TTL = remaining lifetime); check on every request | immediate logout, but **adds a lookup → loses statelessness** |
| **3. Token versioning** | store `token_version` per user; embed it in the JWT; bump version on logout; reject if JWT version ≠ DB version | immediate logout (per-user), needs a lookup |
| **4. Session ID in JWT** | embed `session_id`; validate the session in DB on each request | precise per-device logout; essentially session-backed JWT |

> **Reality:** most large systems (Netflix, Amazon) use **short-lived access + refresh-token deletion** and accept the tiny risk window. High-security systems add a **blacklist** or go **session-based**.

### Blacklist on logout (Redis, with auto-expiry)

```js
const decoded = jwt.decode(token);
const ttl = decoded.exp - Math.floor(Date.now() / 1000);  // remaining lifetime
await redis.set(`bl:${token}`, "1", "EX", ttl);           // expires itself → no leak
// on each request: if (await redis.get(`bl:${token}`)) reject;
```

---

## 11. Why "just check the refresh token on each request" Doesn't Work

> A common (smart but flawed) idea: *"on logout we delete the refresh token, so on each API request just check if the user still has a refresh token."*

**Why it breaks:**

1. **API requests don't carry the refresh token** — only the access token is sent. Forcing the client to send the refresh token on every request is a **security risk** (long-lived secret exposed repeatedly) and kills statelessness.
2. **A user has multiple refresh tokens** (phone, laptop, tablet). "Does the user have *a* refresh token?" → yes (laptop), so a logged-out phone's JWT would still pass ❌.
3. **No mapping** between a JWT and a specific refresh token (the JWT just has `user_id`).
4. **Refresh tokens expire naturally** → you'd reject still-valid access tokens.
5. **DB lookup every request** → loses JWT's main benefit.

> **The clean version of this idea** is to embed a **`session_id`** in the JWT and validate *that* (see §13) — which is essentially session-backed JWT.

---

## 12. Refresh Token Security (it's like a password)

> If a refresh token is stolen, an attacker can mint new access tokens and act as you. Protect it heavily.

| Defense | Why |
| --- | --- |
| **HttpOnly cookie** (not `localStorage`) | JS can't read it → safe from XSS |
| **HTTPS only** | prevents network interception |
| **Refresh token rotation** | every use issues a new refresh token + invalidates the old; **reuse of an old one = theft detected** |
| **Store metadata** (device, IP, created_at) | validate consistency; flag anomalies |
| **Short-ish lifetime** | limits damage window |
| **Anomaly detection** | token used in India then suddenly US → force logout + alert |
| **Logout = delete refresh token** | attacker can't refresh anymore |

### Refresh token rotation

```
Client → /refresh (token A)
Server → validate A → DELETE A → create B → return B
Later, attacker reuses A → not found → reject + (optionally) revoke the whole chain
```

### Where should the client store tokens? (XSS vs CSRF)

> There's **no perfect spot** — each option trades one attack for another. Know the tradeoff:

| Storage | XSS risk | CSRF risk | Notes |
| --- | --- | --- | --- |
| `localStorage` / `sessionStorage` | ❌ **High** — any injected JS can read it | ✅ None (not auto-sent) | convenient but a single XSS = token theft |
| **Cookie** (`HttpOnly`, `Secure`, `SameSite`) | ✅ JS can't read it | ⚠️ **Cookies auto-sent → CSRF** | mitigate CSRF with `SameSite=Strict/Lax` + CSRF token |
| **In-memory** (JS variable) | ⚠️ medium (lost on refresh) | ✅ None | common for the **access** token; pair with a refresh token in an HttpOnly cookie |

- **XSS** (cross-site scripting): attacker runs JS in *your* page → reads anything JS can reach → defend by **not** putting tokens where JS can read them (`HttpOnly` cookie) + sanitizing input + CSP.
- **CSRF** (cross-site request forgery): attacker's site triggers a request to *your* site and the browser **auto-attaches your cookie** → defend with **`SameSite` cookies + CSRF tokens**. (Token-in-`Authorization`-header is naturally CSRF-safe because it isn't auto-sent.)

> **Common modern pattern:** access token in **memory**, refresh token in an **HttpOnly + Secure + SameSite cookie**.

---

## 13. Multiple Devices / Sessions

> "Multiple logins" = same user on phone + laptop + tablet. **Each login = its own session = its own refresh token.**

### Strategies

| Strategy | Behavior | Used by |
| --- | --- | --- |
| **Allow multiple** (most common) | each device gets its own tokens; all stay logged in | Netflix, Amazon, Gmail |
| **Single session** | new login deletes all old refresh tokens (force logout others) | banking, high-security |
| **Limited sessions** | allow N devices; remove the oldest beyond N | OTT device limits |

### Per-device control needs a `session_id` in the JWT

```json
{ "user_id": 123, "session_id": "sess_1", "exp": 1710000000 }
```

```
Validate: verify JWT → extract session_id → check session is active in DB → allow/reject
Logout one device:  deactivate/delete that session_id  → only that device logs out
Logout all devices: deactivate all sessions for the user
```

> Without `session_id` (JWT only has `user_id`) you **cannot** log out a specific device or list active devices.

---

## 14. DB Schema (sessions / refresh tokens)

```sql
CREATE TABLE user_sessions (
    session_id    VARCHAR(64) PRIMARY KEY,
    user_id       BIGINT NOT NULL,
    refresh_token VARCHAR(255) NOT NULL UNIQUE,
    device_name   VARCHAR(100),
    device_type   VARCHAR(50),
    ip_address    VARCHAR(50),
    created_at    TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    expires_at    TIMESTAMP,
    is_active     BOOLEAN DEFAULT TRUE
);
```

```
Logout one device:  UPDATE user_sessions SET is_active = FALSE WHERE session_id = 'sess_1';
Logout all:         UPDATE user_sessions SET is_active = FALSE WHERE user_id = 123;
```

- **Soft delete** (`is_active = FALSE`) → keeps history/audit.
- **Hard delete** (`DELETE`) → cleaner, less storage.
- At scale, mirror this in **Redis** (`session_id → active`) for fast lookups.

---

## 15. Sample Code (Node.js / Express)

> Same concepts in Java/Spring. In-memory stores shown — use **Redis/DB** in production.

```js
const jwt = require("jsonwebtoken");
const { v4: uuid } = require("uuid");
const SECRET = process.env.JWT_SECRET;

const refreshTokens = new Map();  // refreshToken → userId   (use Redis/DB)
const blacklist     = new Set();  // blacklisted access tokens (use Redis)

const genAccess  = (userId) => jwt.sign({ userId }, SECRET, { expiresIn: "15m" });
const genRefresh = (userId) => { const t = uuid(); refreshTokens.set(t, userId); return t; };

// LOGIN → issue both tokens
app.post("/login", (req, res) => {
  const user = verify(req.body);                 // validate credentials
  if (!user) return res.status(401).send("Invalid credentials");
  res.json({ accessToken: genAccess(user.id), refreshToken: genRefresh(user.id) });
});

// MIDDLEWARE → validate access token
function authenticate(req, res, next) {
  const token = (req.headers.authorization || "").split(" ")[1];
  if (!token) return res.status(401).send("No token");
  if (blacklist.has(token)) return res.status(401).send("Logged out");
  jwt.verify(token, SECRET, (err, decoded) => {
    if (err) return res.status(403).send("Invalid token");
    req.user = decoded; next();
  });
}

// REFRESH → new access token from a valid refresh token
app.post("/refresh", (req, res) => {
  const { refreshToken } = req.body;
  if (!refreshTokens.has(refreshToken)) return res.status(403).send("Invalid refresh token");
  res.json({ accessToken: genAccess(refreshTokens.get(refreshToken)) });
});

// LOGOUT → delete refresh token + blacklist current access token
app.post("/logout", (req, res) => {
  const { refreshToken, accessToken } = req.body;
  refreshTokens.delete(refreshToken);
  blacklist.add(accessToken);
  res.send("Logged out");
});
```

| In-memory (demo) | Production |
| --- | --- |
| `Map` / `Set` | Redis / DB |
| hardcoded `SECRET` | secret in env / KMS |

---

## 16. Auth System Design (API Gateway + Redis + JWT)

```
Client
  ↓
API Gateway        → validates JWT (signature + expiry), no DB call
  ↓ (forwards user_id)
Microservices      → trust the gateway, just read user_id
  ↑
Auth Service       → login / refresh / logout (issues + revokes tokens)
  ↓
Redis / DB         → refresh tokens, sessions, blacklist, rate limiting
```

**Flow:** login at Auth Service → get access + refresh → all API calls validated at the gateway → `/refresh` for new access tokens → logout deletes the refresh token (and optionally blacklists the access token).

> Where Redis fits: refresh-token/session store, JWT **blacklist**, and **rate limiting**.

---

## 17. OAuth2, OIDC & SSO ("Login with Google")

> Everything above is **first-party** auth (you own the users). When you want *"Log in with Google/GitHub"* or one login across many apps, you use **OAuth2** + **OpenID Connect**.

| Term | What it is |
| --- | --- |
| **OAuth2** | a **delegated authorization** framework — lets app A access resources on B *on your behalf* **without your password** |
| **OIDC** (OpenID Connect) | an **authentication** layer on top of OAuth2; adds an **ID token** (a JWT about *who you are*) |
| **SSO** (Single Sign-On) | one login → many apps (e.g. all Google products); usually built on OIDC/SAML |

### Authorization Code flow (the common one)

```
1. User clicks "Login with Google"
2. App redirects to Google (the Authorization Server) with client_id + scopes
3. User authenticates with Google + consents
4. Google redirects back with a short-lived  authorization code
5. App's backend exchanges  code + client_secret  → access token + ID token (server-to-server)
6. App verifies the ID token (a JWT, RS256) → knows who the user is
```

- The **code → token exchange happens on the backend** so tokens aren't exposed in the browser/URL.
- Public clients (SPAs, mobile) add **PKCE** (proof key) to protect the code exchange without a client secret.
- Key roles: **Resource Owner** (user), **Client** (your app), **Authorization Server** (Google), **Resource Server** (the API).

> **Interview line:** "OAuth2 is about *delegated authorization*; OIDC adds *authentication* via an ID token. 'Login with Google' is the OIDC Authorization Code flow — we never see the user's Google password, just a verifiable ID token."

---

## 18. Interview Cheat Sheet

> **"How is a login session maintained?"**
>
> "Either a **session** stored server-side (Redis) with a session-ID cookie, or a stateless **JWT**. Modern systems prefer JWT for scalability; for security, access tokens are short-lived and refresh tokens issue new ones."

> **"Who creates the JWT signature?"**
>
> "The backend, using a secret (HMAC) or private key. The client can't forge or modify it; the server recomputes and compares on each request."

> **"How does logout work in JWT?"**
>
> "JWT is stateless, so you can't delete it. Use **short-lived access tokens + refresh-token deletion**; for immediate logout add a **blacklist** or **token versioning**."

> **"What if the refresh token is stolen?"**
>
> "An attacker could mint access tokens. Mitigate with **HttpOnly cookies, HTTPS, refresh-token rotation, device binding, short lifetimes, and anomaly detection**."

> **"How do you support multiple devices / per-device logout?"**
>
> "Each login is a separate session with its own refresh token and `session_id` embedded in the JWT. Logout deactivates that session; logout-all deactivates every session for the user."

> **"HS256 vs RS256 — which and why?"**
>
> "HS256 uses one shared secret to sign and verify — fine for a single service. RS256 signs with a private key and verifies with a public key, so a gateway or many services can validate tokens without holding forging power. Microservices and 'Login with Google' use RS256."

> **"Where do you store the token on the client?"**
>
> "Access token in memory, refresh token in an HttpOnly + Secure + SameSite cookie. localStorage is XSS-prone; cookies need CSRF protection (SameSite + CSRF token)."

---

## 19. Final Takeaways

```
Session = server remembers you (stateful, easy revoke)
JWT     = you carry signed identity (stateless, scalable)
Access token  = short-lived pass (used for APIs)
Refresh token = long-lived, server-controlled (gets new access tokens)
```

- **Server signs** the JWT; payload is readable (base64), not secret.
- **Logout is JWT's weak spot** → short expiry + refresh deletion, or blacklist/versioning for instant logout.
- **Refresh tokens are like passwords** → protect with HttpOnly + HTTPS + rotation.
- **Multiple devices** → multiple sessions; embed `session_id` for per-device control.
- Deleting a refresh token only stops **future** access tokens, not the current one.
