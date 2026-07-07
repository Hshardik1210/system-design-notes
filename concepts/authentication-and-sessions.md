# Authentication & Sessions (Login, JWT, Refresh Tokens)

> **The core question:** after a user logs in, *"how do we remember this user on future requests?"* Two answers: **sessions** (server remembers you) and **JWT** (you carry your signed identity).

> **How to read this doc:** dense summary first, then Plain-English deep dive. Each section opens with the tight interview-style summary; a **Plain-English** subsection follows with everyday analogies (a **club wristband** = session cookie; a **signed hall pass** = JWT), annotated example code, and the exact confusions beginners hit. Skim the summaries for revision; read the Plain-English parts to actually *get* it.

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

### Plain-English: the nightclub door problem

Imagine a **busy nightclub**. You show your ID at the door **once** and pay the cover. Now you want to go outside for air and come back — walk to the bar — hit the restroom. The bouncer sees you **every time**, but you obviously don't want to show your ID and re-pay at every doorway.

That is *exactly* the web's problem. HTTP has **no memory**: every request (every "doorway") looks brand-new to the server, as if it had never seen you. Logging in once and then loading 50 pages = walking through 50 doorways. Something has to prove "I already paid" each time — **without redoing the whole login**.

Two real-world ways clubs solve this, and they map 1:1 to our two auth styles:

- **Club wristband** → the bouncer keeps a list ("wristband #A17 = paid VIP"). The band itself is dumb; its meaning lives on the club's clipboard. This is a **session**: the server remembers who you are, the cookie is just a claim ticket.
- **Signed hall pass** → the club hands you a pass that's **stamped/signed** with something only the club can produce. The bouncer doesn't need a clipboard; they just check the stamp is real. This is a **JWT**: the token *carries* your identity, proven by a signature.

#### Q: Why not just send the email + password on every request?

You *could*, but: (1) the browser would have to **store your raw password** to resend it — one leak and it's game over; (2) the server would **re-verify the password (slow bcrypt hash) on every single request**; (3) any logging/proxy in the middle now sees your password constantly. So we log in **once**, then carry a cheaper, revocable, expiring proof (wristband or hall pass) afterward.

#### Q: What does "stateless HTTP" actually mean here?

"**Stateless**" = the server keeps **no built-in memory of past requests**. Request #2 doesn't know request #1 happened. Any "memory" (you're logged in) has to be **re-established on each request** — either by the server looking you up (session) or by you presenting a self-proving token (JWT). That single fact is the root of everything in this doc.

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

### Plain-English: the club wristband

**Analogy:** you pay at the door and get a **wristband with a random number, say #A17**. The club keeps a clipboard: *"#A17 → VIP, paid, entered 9:04pm."* Every doorway inside, the bouncer glances at your band and checks the clipboard. The band itself means nothing — all the real info lives on the **club's clipboard** (the server's session store). If they cross #A17 off the clipboard, your band is instantly worthless (that's **logout**).

- **The wristband = the session cookie** (`session_id=abc123`). Just a random claim-ticket number.
- **The clipboard = the session store** (Redis/DB): `session_id → user_id, expiry, ...`.
- **HttpOnly** = the band is glued on so *your own phone's JavaScript can't peel it off and read/steal it* (XSS defense).

#### Annotated session-cookie flow

```
# 1. You log in — server checks password, then makes a wristband
POST /login {email, password}
   server: verify password ✅
   session_id = "abc123"                 # random, unguessable number
   redis.set("abc123", {user_id: 7})     # write it on the clipboard
   Set-Cookie: session_id=abc123; HttpOnly; Secure; SameSite=Lax

# 2. Every later request — browser AUTO-sends the cookie, server checks clipboard
GET /profile
   Cookie: session_id=abc123             # browser attaches it for you
   user = redis.get("abc123")            # clipboard lookup → {user_id: 7}
   → authenticated as user 7 ✅

# 3. Logout — cross it off the clipboard; band is now dead
POST /logout
   redis.del("abc123")                   # instantly revoked everywhere
```

#### Q: If the cookie is stolen, can't someone reuse it?

Yes — a stolen wristband works until the club voids it. That's why session cookies are `HttpOnly` (JS can't read them), `Secure` (HTTPS only, not sniffable), and `SameSite` (limits CSRF). But the *upside* of sessions is the flip side: because the meaning lives on the clipboard, the server can **instantly kill** a stolen session (`redis.del`). Revocation is a **one-line delete** — that's sessions' superpower.

#### Q: Why is "stateful" considered a downside?

Every request costs a **clipboard lookup** (a round-trip to Redis/DB), and every server that handles your requests must be able to reach that **same shared clipboard**. With 10 servers behind a load balancer, they all need one shared session store — extra infra and a lookup on *every* request. JWT (next) removes that lookup, which is why big scaled-out APIs lean toward it.

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

### Plain-English: the signed hall pass

**Analogy:** instead of a clipboard, the club gives you a **hall pass** with your details written on it — *"Bearer: user 7, VIP, expires 11:00pm"* — and **stamps it with a special seal** only the club owns. Now the bouncer needs **no clipboard**: they just check the seal is genuine and the time hasn't passed. The pass **carries** everything; the club doesn't have to remember you at all.

- **The written details = the JWT payload** (`user_id`, `role`, `exp`). Anyone can read them.
- **The tamper-proof seal = the signature.** Only the server (holding the SECRET) can produce it; anyone can *check* it, nobody can *forge* it.
- **No clipboard = no DB lookup.** This is why JWT scales: verifying is pure math on the token itself.

#### Annotated JWT flow

```
# 1. Log in — server verifies password, then STAMPS a hall pass
POST /login {email, password}
   server: verify password ✅
   jwt = sign({ user_id: 7, role: "vip", exp: now+15min }, SECRET)
   return jwt                              # nothing stored server-side!

# 2. Every later request — YOU carry the pass in a header
GET /profile
   Authorization: Bearer eyJhbGci...       # you send it (not auto-sent like a cookie)
   verify(jwt, SECRET) ✅                   # recompute the seal, compare — no DB call
   exp > now? ✅                            # not expired
   → user_id 7, straight from the token

# 3. Logout — ...awkward (see §10). The club already handed you the pass;
#    it can't reach into your pocket and tear it up. It just expires soon.
```

#### Q: The payload is readable — isn't that insecure?

The pass is **signed, not sealed in an envelope**. The details are only **base64-encoded** (a reversible format, *not* encryption) — anyone who intercepts the token can read `user_id: 7, role: vip`. That's fine, because JWT guarantees **integrity** ("nobody changed this"), *not* **confidentiality** ("nobody can read this"). So: never put passwords or secrets in a JWT. If you flip `role: "vip"` → `role: "admin"`, the seal no longer matches and the server rejects it.

#### Q: If nothing is stored server-side, how do we ever revoke it?

You mostly **can't**, until it expires — that's the core trade-off (full treatment in §10). A hall pass already in your pocket stays valid till its printed expiry. That's why JWTs are made **short-lived** and paired with a **refresh token** (§8) that *is* stored server-side and can be revoked.

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

### Plain-English: wristband vs hall pass, side by side

The whole table boils down to **where the truth lives**:

- **Session (wristband):** truth is on the **club's clipboard**. The cookie is a dumb pointer. → server must be asked every time (lookup), but can **instantly void** your band.
- **JWT (hall pass):** truth is **written on the pass itself**, sealed. → no asking needed (fast, scales), but the club can't grab it back once handed out (hard to revoke).

Everything else — statefulness, scalability, revocation ease — falls out of that one difference.

#### Q: "Session vs token" — aren't they kind of the same thing? I'm confused.

They both solve "remember me after login," but they're opposites in mechanism:

| Question | Session | JWT (token) |
| --- | --- | --- |
| Who *remembers* you? | the **server** (clipboard) | **you** (you carry the pass) |
| What's in the cookie/token? | a **random id**, meaningless alone | your **actual identity**, signed |
| Cost per request | a **lookup** (Redis/DB) | a **signature check** (pure CPU) |
| Instant logout? | **Yes** — delete the row | **No** — valid till it expires |

Rule of thumb: need **easy revocation / high security** (banking) → sessions. Need **scale / stateless services / many APIs** → JWT (usually with short expiry + refresh tokens to claw back the revocation weakness).

#### Q: Which do real companies use?

Often **both, layered**: a JWT **access token** for speed on every API call, plus a server-stored **refresh token** (session-like) to control long-term login and enable logout (§8). You get JWT's speed *and* a revocation lever.

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

### Plain-English: reading a hall pass

A JWT is one long string with **three parts glued by dots**: `header.payload.signature`. Think of the hall pass as having three printed zones:

```
eyJhbGciOiJIUzI1NiJ9  .  eyJ1c2VyX2lkIjoxMjN9  .  ABCxyz...
────── header ──────     ───── payload ─────      ─ signature ─
"how it's sealed"        "what it says"           "the seal itself"
```

- **Header** = the recipe for the seal — *"sealed with HS256."* (So the verifier knows how to re-check it.)
- **Payload** = the human-readable facts — *"user 123, role user, expires 2:15."*
- **Signature** = the tamper-proof **wax seal**. Made from `header + payload + SECRET`. Change one letter of the payload and the seal no longer matches.

#### How the seal makes forgery impossible (annotated)

```
# The server MAKES the seal (only it knows SECRET):
signature = HMAC_SHA256( base64(header) + "." + base64(payload), SECRET )

# On each request the server RE-MAKES it from the parts you sent, and compares:
expected = HMAC_SHA256( received_header + "." + received_payload, SECRET )
if expected == received_signature:  # seal matches → untampered ✅
else:                               # someone edited the pass → reject ❌
```

An attacker can *read and even edit* the payload (`role: user` → `role: admin`), but they **can't regenerate a matching seal** without the SECRET — so the edit is caught instantly. That's the entire security model: **you can't fake the seal.**

#### Q: Base64 vs encryption — what's the difference?

**Base64 is not encryption.** It's just a reversible way to pack bytes into URL-safe text — like writing in a different alphabet, *not* a locked box. Paste the payload into any base64 decoder and you'll read `{"user_id":123}` plainly. So a JWT hides *nothing*; it only *proves nobody changed it*. Never store passwords, card numbers, or secrets in a JWT payload.

#### Q: What are `sub`, `iss`, `aud`, `exp`... those cryptic short names?

They're **standard claim abbreviations** so different systems agree on meaning: `sub` = *subject* (who it's about), `iss` = *issuer* (who minted it), `aud` = *audience* (which API it's for), `exp` = *expiry*, `iat` = *issued at*, `nbf` = *not valid before*, `jti` = *unique token id*. A careful verifier checks `exp`, `nbf`, `iss`, and `aud` — not just the seal — so a token minted for App A can't be replayed against App B.

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

### Plain-English: the bouncer checking the pass, in code

The bouncer does the same 6 steps for every doorway. Here it is as annotated middleware — notice there is **no database call anywhere**:

```js
function checkJwt(req, res, next) {
  // 1. pull the pass out of the header:  "Authorization: Bearer <jwt>"
  const token = (req.headers.authorization || "").split(" ")[1];
  if (!token) return res.status(401).send("No token");   // no pass → no entry

  try {
    // 2 + 3. verify() does BOTH: re-checks the seal (SECRET) AND the exp claim.
    //        Throws if the seal is wrong OR the pass is expired.
    const decoded = jwt.verify(token, SECRET);            // pure math, no DB

    // 4 + 5. trust the payload; attach the user to the request
    req.user = decoded.user_id;                           // e.g. 123

    // 6. let the request proceed
    next();
  } catch (err) {
    // bad seal (tampered / wrong key) OR expired → reject
    return res.status(401).send("Invalid or expired token");
  }
}
```

#### Q: Why is "no DB lookup" such a big deal?

Because it's what lets you run **100 identical stateless servers** behind a load balancer with **zero shared state**. Any server can validate any request using just the SECRET (or public key) in memory — no round-trip to Redis, no "which server holds my session?" problem. A viral traffic spike? Just add more servers; there's no shared clipboard to become a bottleneck.

#### Q: Then how does an expired or logged-out token get rejected?

**Expiry** is checked right here (step 3 / `jwt.verify` reads `exp`) — no DB needed. **Logout** is the hard case: since we never looked anything up, there's nothing to "delete." That gap is exactly what §10 (logout) and §8 (refresh tokens) exist to solve.

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

### Plain-English: the bouncer checking the clipboard, in code

Same idea as §6, but instead of checking a seal, the bouncer **reads the wristband number and looks it up on the clipboard**:

```js
async function checkSession(req, res, next) {
  // 1. read the wristband number from the cookie
  const sid = req.cookies.session_id;                 // "abc123"
  if (!sid) return res.status(401).send("No session");

  // 2 + 3. look it up on the clipboard (Redis). Deleted/expired → nothing comes back.
  const session = await redis.get(sid);               // ← the lookup (stateful!)
  if (!session) return res.status(401).send("Session invalid or expired");

  // 4 + 5. attach user, proceed
  req.user = session.user_id;
  next();
}
```

#### Q: This does a Redis call every request — isn't that slow?

It's a tiny, in-memory Redis GET (sub-millisecond), so it's *fast* — but it's still a **network round-trip on every request**, and every app server must reach the same Redis. That's the price of being **stateful**. In exchange you get the thing JWT struggles with: **instant revocation** — `redis.del(sid)` and the user is logged out *everywhere, immediately*. No waiting for a token to expire.

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

### Plain-English: the day-pass and the membership card

**Analogy:** at a theme park you get two things:

- A **day-pass wristband** you flash at every ride — but it **self-destructs every 15 minutes**. If someone snatches it, it's near-worthless in minutes. → the **access token (JWT)**.
- A **membership card** kept in your wallet, shown only at the **renewal desk** to get a fresh day-pass. The park keeps a record of your card and can **cancel it** (that's logout). → the **refresh token**.

Why the split? A JWT can't be revoked, so you daren't make it long-lived (a stolen long JWT = long-lived break-in). But forcing the user to *re-type their password* every 15 minutes is miserable. The refresh token squares this circle: the **access token stays short (safe)**, while the **refresh token quietly renews it in the background (convenient)** — and because the refresh token *is* stored server-side, the server keeps a revocation lever.

#### Annotated refresh flow

```
# Login: get BOTH
POST /login → { accessToken (15m JWT), refreshToken (7d, also saved in DB) }

# Normal use: only the short day-pass is sent
GET /orders   Authorization: Bearer <accessToken>     # works for 15 min

# 15 min later the day-pass has self-destructed:
GET /orders   → 401 Unauthorized (expired)

# Client silently visits the renewal desk with the membership card:
POST /refresh { refreshToken }
   server: is this refreshToken still in the DB (not revoked/expired)? ✅
   → issue a fresh accessToken (another 15m)      # NO password needed
# user never notices; they keep browsing

# Logout: cancel the membership card
POST /logout → server DELETES refreshToken from DB
   → next /refresh fails → once the current 15m pass dies, they're fully out
```

#### Q: If the access token is a JWT, why does expiry even matter — can't I just make it last 7 days?

You *could*, but then a stolen token = **7 days** of attacker access with **no way to shut it off** (JWTs can't be revoked). Short expiry means a leaked token is a **15-minute problem, not a week-long one**. The refresh token carries the "stay logged in for 7 days" burden instead — and *it* can be revoked, because it lives in the DB. Short access + revocable refresh = safety **and** convenience.

#### Q: Doesn't `/refresh` hitting the DB defeat JWT's "no lookup" benefit?

No — because `/refresh` runs **rarely** (once every ~15 min), while your **actual API calls** (many per second) still validate the JWT with **zero DB lookups**. You pay the lookup cost only at renewal time, not on the hot path. Best of both worlds.

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

### Plain-English: only the park prints the cards

**Analogy:** the theme park prints both the day-pass and the membership card. **You never bring your own.** If visitors could print their own membership cards, the park could never cancel one (you'd just print another) — the whole "cancel a card to log someone out" mechanism collapses.

- The **backend generates both tokens**; the client only **stores and re-presents** them.
- The refresh token is written into the server's records (`refresh_token → {user_id, device, expiry}`), which is precisely *what makes logout possible* — you delete that record.
- Note the refresh token is usually just a **random string (UUID)**, not a JWT. It doesn't need to *carry* info; its meaning lives in the DB row (like a session/wristband). Its whole job is "look me up and prove I'm still allowed."

#### Q: Why is the refresh token a random UUID, but the access token a JWT?

Different jobs. The **access token** must be verifiable **without a lookup** (used constantly) → it carries signed claims (JWT). The **refresh token** is used **rarely** and *must* be revocable → so it's a plain random id whose truth lives in the DB, exactly like a session. In short: access token = *self-proving* (JWT), refresh token = *server-checked* (session-like).

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

### Plain-English: you can't un-hand a hall pass

**Analogy:** the wristband club can cross #A17 off the clipboard and you're instantly out (session logout = easy). But the **hall-pass club already handed you a signed pass** — it can't reach into your pocket and shred it. Until the printed expiry passes, that pass still opens doors. **That's the JWT logout problem in one sentence.**

So "logout" for JWT is really a set of workarounds, trading statelessness for control:

- **Just wait it out (most common):** make the pass expire in 15 min, and on logout **delete the refresh token** so no *new* passes get printed. Accept that the current pass works for its last few minutes.
- **Blacklist (Redis):** keep a "banned passes" list and check it every request. Instant logout — but now you're doing a lookup again, so you've **given back JWT's statelessness** for those tokens.
- **Token versioning:** stamp a version number on the pass; bump the user's version on logout; reject any pass with an old version.

The blacklist code above is clever about one thing: it sets the Redis entry's **TTL to the token's remaining lifetime**, so the ban **auto-cleans itself** the moment the token would have expired anyway — no permanent junk piling up.

#### Q: So is JWT logout just... broken?

Not broken — **a deliberate trade-off**. You chose JWT for speed/scale (no lookups); the cost is you gave up cheap instant revocation. The industry answer is "short access token + delete the refresh token," which makes the leak window tiny (minutes) without adding a per-request lookup. Only when you need **truly instant** logout do you add a blacklist/versioning (and eat the lookup) — or use sessions instead.

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

### Plain-English: why the "obvious" logout fix backfires

The tempting idea: *"On logout I delete the refresh token, so on every request just check the user still has a refresh token."* It sounds neat, but it quietly breaks:

- **The day-pass and membership card are separate.** Every request carries only the **access token (day-pass)** — the **refresh token (membership card)** stays home. Making the browser send the long-lived, precious refresh token on *every* request re-exposes a password-like secret constantly (a security downgrade) and kills statelessness.
- **You have many cards.** Phone, laptop, tablet = three refresh tokens. "Does the user have *some* refresh token?" is *yes* (laptop still logged in) even though you just logged out the *phone* — so the phone's pass wrongly keeps working.
- **No link between a given pass and a given card.** The JWT only says `user_id: 7`; it doesn't know *which* device/refresh-token minted it.
- Plus it puts a **DB lookup back on every request** — the very thing JWT was chosen to avoid.

The correct version: embed a **`session_id`** in the JWT (§13) and check *that specific session* — precise per-device logout, at the cost of a lookup you opted into.

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

### Plain-English: XSS vs CSRF, without the jargon

These two attacks confuse everyone because both involve "someone doing bad things with your login." The key is **who is tricked**:

- **XSS (Cross-Site Scripting) — the attacker runs *their* code inside *your* page.** Analogy: a con artist sneaks into your house wearing an employee badge and now roams freely, reading whatever's lying around. If your token sits in `localStorage`, that injected JavaScript just **reads it and walks out with it**. → **Defense: don't leave the token where JS can grab it.** Put it in an `HttpOnly` cookie (JS literally cannot read it), sanitize user input, and set a Content-Security-Policy.

- **CSRF (Cross-Site Request Forgery) — the attacker never sees your token; they trick your *browser* into using it.** Analogy: you're logged into your bank in one tab. A shady site in another tab has a hidden form that auto-submits `POST yourbank.com/transfer`. Your **browser helpfully auto-attaches your bank cookie**, so the request looks legit — the attacker steered your authenticated browser without ever *reading* anything. → **Defense: `SameSite` cookies** (don't send the cookie on cross-site requests) **+ a CSRF token** the attacker can't guess.

#### The storage trade-off (there is no perfect spot)

```
localStorage   → JS can read it        → great vs CSRF, TERRIBLE vs XSS (one XSS = token stolen)
HttpOnly cookie→ JS can't read it,     → great vs XSS, but auto-sent → needs CSRF protection
                 browser auto-sends it
in-memory var  → gone on page refresh  → no XSS-persistence, not auto-sent → common for access token
```

- Notice the seesaw: the thing that stops XSS (a cookie JS can't read, auto-sent by the browser) is the *exact* thing that enables CSRF (auto-sent). You can't dodge both by picking one bucket — you pick a bucket **and** add the matching mitigation.
- A token sent in an `Authorization: Bearer` header is **naturally CSRF-safe**, because the browser does **not** auto-attach it (your JS has to add it deliberately). That's why the popular combo is: **access token in memory + `Authorization` header** (CSRF-safe), **refresh token in an `HttpOnly` cookie** (XSS-safe), with `SameSite` on top.

#### Q: One-line each — what's the difference?

- **XSS** = *your page runs the attacker's script* → it can **read** your token. Fix: keep tokens out of JS's reach (`HttpOnly`).
- **CSRF** = *the attacker's page rides your logged-in browser* → it **uses** your cookie without reading it. Fix: `SameSite` + CSRF token.

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

### Plain-English: one membership card per device

**Analogy:** log in on your phone, laptop, and tablet and the park issues you **three separate membership cards**, each with its own card number. "Log out my lost phone" = **cancel just that one card**; the laptop and tablet keep working. "Log out everywhere" (you think you were hacked) = **cancel all three**.

- **Each login = its own session = its own refresh token.** They're independent.
- To target *one* device, the JWT must carry a **`session_id`** so the server knows *which* card this pass came from. With only `user_id`, all your devices look identical — you can't single one out.

```
Phone   → session "sess_1" → refresh token R1
Laptop  → session "sess_2" → refresh token R2
Tablet  → session "sess_3" → refresh token R3

Logout phone      → deactivate sess_1 only        (laptop + tablet unaffected)
Logout everywhere → deactivate sess_1, sess_2, sess_3
"Active devices"  → list all sessions for user 7  (this is your "logged-in devices" screen)
```

#### Q: Why can't I just log out "the user" instead of a session?

Because "the user" is logged in on **multiple devices at once**, and usually you want to kill **one** (the stolen phone) while keeping the others. Tracking per-**session** (not just per-**user**) is what powers the "Your active sessions / Sign out this device" screens you see in Gmail, Netflix, etc. The `session_id` is the handle that makes each device individually addressable.

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

### Plain-English: this table *is* the club's clipboard

Every row is **one membership card the park has on file** — one device's login. Reading it column by column:

- `session_id` / `refresh_token` → the card's number and its secret code.
- `user_id` → whose card it is.
- `device_name`, `device_type`, `ip_address` → the "logged-in devices" list you show the user, and the clues anomaly-detection uses ("card #2 suddenly used from another country").
- `expires_at` → when the card auto-dies.
- `is_active` → the on/off switch. **Logout just flips this to `FALSE`** instead of erasing the row.

#### Q: Why "soft delete" (`is_active = FALSE`) instead of actually deleting the row?

Flipping a flag **keeps the history** — useful for audit ("when/where did this device sign out?"), security investigations, and showing past sessions. Hard `DELETE` is cleaner and saves space but forgets everything. Many systems soft-delete, then purge old inactive rows on a schedule. Either way, the effect on the user is identical: the card no longer works.

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

### Plain-English: walking the four endpoints

Every concept in this doc shows up in those ~40 lines. Mapped to the theme-park story:

- **`/login`** → *print both passes.* `verify()` checks the password; `genAccess` stamps a 15-min JWT day-pass; `genRefresh` mints a random-UUID membership card and **files it** in `refreshTokens` (the DB stand-in).
- **`authenticate` middleware** → *the bouncer.* Pulls the pass from the `Authorization` header, checks it's not blacklisted, then `jwt.verify` re-checks the seal + expiry. **No DB call** for the common case — the whole point of JWT.
- **`/refresh`** → *the renewal desk.* Takes the membership card, confirms it's still on file (`refreshTokens.has`), and stamps a fresh day-pass. This is the *only* spot that touches the store on the happy path.
- **`/logout`** → *cancel + ban.* Deletes the refresh token (no more renewals) **and** blacklists the current access token (kills the last 15-min window immediately).

#### Q: The demo uses `Map` and `Set` — why does the table say "use Redis/DB"?

An in-memory `Map` lives inside **one** server process: it's wiped on restart, and a *second* server wouldn't see it — so refresh tokens and the blacklist would silently disagree across your fleet. **Redis/DB is a shared clipboard all servers read** and it survives restarts. Likewise the `SECRET` must come from an **env var / secrets manager (KMS)**, never hardcoded — leak the secret and anyone can forge valid passes.

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

### Plain-English: the gatehouse and the specialist office

**Analogy:** picture a corporate campus. The **API Gateway is the front gatehouse** — every visitor's badge is checked *here*, once, before they're let onto the grounds. Inside, individual buildings (**microservices**) don't re-check badges; they trust that the gatehouse already did, and just read the visitor's name off the slip the gatehouse forwarded (`user_id`). The **Auth Service is a specialist office** that *issues* and *cancels* badges (login/refresh/logout), and **Redis is its filing cabinet** (refresh tokens, blacklist, rate-limit counters).

- **Gateway = fast, stateless JWT check** on every request → no DB call, so it scales to huge traffic.
- **Microservices trust the gateway** → they don't each re-validate; they read the forwarded `user_id`. (This is why **RS256** is handy here — services verify with a *public* key and can't mint tokens.)
- **Auth Service = the only place that mints/revokes** → keeps the signing key and the refresh/session state in one controlled spot.

#### Q: If the gateway already validated the token, why keep Redis around at all?

Because some things **can't** be answered by a signature alone: *has this refresh token been revoked?* (blacklist/session store), *is this IP hammering `/login`?* (rate limiting). The JWT check handles the fast, stateless "is this pass genuine and unexpired?"; Redis handles the small set of **stateful** questions where you need live, shared, revocable memory.

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

### Plain-English: the hotel valet key

**Analogy:** you hand the valet a **valet key**, not your house-and-office keyring. The valet key **only starts the car and opens the door** — it *can't* open the glovebox or trunk. You **delegated a limited power** without giving away full access, and you never handed over your master keys. **That's OAuth2:** you let App A do a *specific, limited* thing on Service B **on your behalf**, *without giving App A your B password*.

- **"Login with Google"** = you tell Google (whom you already trust) "let this app know who I am." The app never touches your Google password — it just receives a **verifiable ID token** (OIDC) that says "this is user Jane, confirmed by Google."
- **Scopes** = which valet powers you granted (`read your email`? `just your name + photo`?). You consent to exactly those.

#### Annotated Authorization Code flow

```
# 1. You click "Login with Google" on TodoApp.
# 2. TodoApp sends your BROWSER to Google (not TodoApp's server):
GET accounts.google.com/authorize
      ?client_id=todoapp             # who is asking
      &scope=openid email            # which valet powers requested
      &redirect_uri=todoapp.com/cb   # where to send you back

# 3. You log in TO GOOGLE and click "Allow".   ← TodoApp NEVER sees this password
# 4. Google sends your browser back with a short-lived one-time CODE:
GET todoapp.com/cb?code=XYZ123       # the code is useless on its own

# 5. TodoApp's BACKEND swaps the code (+ its secret) for tokens, server-to-server:
POST googleapis.com/token
      { code: XYZ123, client_id, client_secret }   # secret proves it's really TodoApp
   → { access_token, id_token }      # id_token = a signed JWT about WHO you are

# 6. TodoApp verifies the id_token's signature (RS256, Google's public key via JWKS)
#    → now it trustably knows "this is jane@gmail.com" — without ever seeing her password.
```

- Why the **code** detour instead of handing tokens straight to the browser? So the actual tokens are exchanged **server-to-server**, never exposed in the URL/browser history. The code alone is worthless without the app's `client_secret`.
- **SPAs/mobile apps** have no safe place for a `client_secret`, so they add **PKCE** — a one-time proof they generated the request — to secure the exchange without a secret.

#### Q: Authentication vs Authorization — what's the actual difference?

They sound alike but answer different questions:

- **Authentication (authn) = "who are you?"** — proving identity (login, the ID token, verifying a JWT's seal). *The bouncer confirming it's really you.*
- **Authorization (authz) = "what are you allowed to do?"** — checking permissions **after** we know who you are (is this user an admin? can they delete this file? the valet key's limited powers). *The bouncer deciding which rooms your VIP band unlocks.*

Mnemonic: **authN = wh*o*** (i**N**dentity), **authZ = privilege*Z*/permissions**. Order matters: you authenticate **first** (establish identity), then authorize (check rights). A JWT's *signature check* is authn; reading `role: admin` from it to gate an action is authz. And note the naming quirk this section hinges on: **OAuth2 is fundamentally an *authoriZation* framework** (delegating limited access = the valet key), while **OIDC bolts *authenticatioN* on top** (the ID token that says *who* you are).

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
