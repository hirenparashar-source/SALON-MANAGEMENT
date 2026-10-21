# Architecture

One process, one port, one folder of JSON. This document explains how a click in the browser
becomes a line in `data/invoices.json`.

## 1 · Composition

`com.sillage.Salon` is the composition root. At boot it:

1. Opens the `DataStore` at `SILLAGE_DATA` and registers 22 repositories, each with its
   secondary indexes (`customers.phone`, `customers.email`, `invoices.number`,
   `appointments.staffId`, `products.sku`, `users.username`, …).
2. Builds the security layer (audit log, session manager, rate limiter).
3. Builds the 19 services in dependency order — `PricingService` receives a small `RepoHolder`
   interface rather than the whole store, so it can only ever *read*.
4. Asks `com.sillage.api.Api` for the 181-route router.
5. Creates the `SalonServer` over `com.sun.net.httpserver` with a fixed 6–24 thread pool.
6. Seeds the demo salon on first boot (`seed/SeedData`, fixed RNG seed `20261021`) and rebuilds
   the search index.

Nothing is wired by reflection or annotation scanning; every dependency is a constructor
argument you can read.

## 2 · A request, end to end

```
browser ── HTTPS-less HTTP ──▶ SalonServer.handle(HttpExchange)
                                 │  build Request (headers, cookies, ip, body ≤ 4 MB)
                                 │  SessionManager.touch(cookie) → SessionRecord → Actor
                                 │  RateLimiter.check(rule for path prefix, ip)   ── 429?
                                 ▼
                               Router.match(method, path) → Handler
                                 │  ApiException → JSON {"error":{code,message,details,at}}
                                 │  other Throwable → 500 JSON (dev) or the editorial 404/500 page
                                 ▼
                               api/* endpoint (a lambda) → service method
                                 │  Actor.require(ROLE) gates every state-changing surface
                                 ▼
                               service → Repo.put → DataStore.scheduleSave
                                 │  debounced 250 ms → AtomicFiles.write (tmp + atomic rename)
                                 ▼
                               Response (JSON / CSV / text / HTML) with security headers,
                               gzip above 1 KB, Cache-Control: no-store for /api/*
```

## 3 · Persistence

* Each collection is one JSON file, `data/<name>.json`, written as a temp file then renamed, so a
  crash mid-write can never truncate the book.
* Writes are debounced (250 ms) and coalesced; `--quiet`/`--dev` change only logging.
* `Repo<T>` keeps an in-memory map plus secondary indexes; reads are lock-free-ish (RW lock),
  writes take the write lock and bump a mutation counter.
* Snapshots (`POST /api/backup`) copy every collection into
  `data/backups/sillage-<timestamp>.json`; `POST /api/restore/:name` replays one and rebuilds the
  search index.

## 4 · Identity

* Sessions are opaque 32-byte tokens; the *record id* is `sha256(token)`, so a stolen database
  file cannot be replayed as a cookie.
* Cookies: `sillage_sid` (HttpOnly, SameSite=Lax) and `sillage_csrf` (readable by the SPA, sent
  back as `X-CSRF-Token` on every mutation).
* Roles form a chain `OWNER ⊃ MANAGER  RECEPTION ⊃ STYLIST ⊃ CLIENT ⊃ GUEST`; `Actor.require`
  throws 403 with a message that says which role was needed.
* Guests get a session too — it is how a public booking keeps its basket and how the
  "manage a booking" flow proves ownership with `code + mobile` instead of a password.

## 5 · Money

`core/Money` is the only place BigDecimal rounding happens (scale 2, HALF_UP). The pricing
invariants are asserted by the test suite and re-derived nightly by `DataService.integrity()`:

```
subtotal = Σ lines where kind ∈ {SERVICE, PRODUCT, ADDON}
base     = subtotal − discount − loyaltyRedemption
total    = base + taxTotal + gratuity + roundOff
```

Tax-inclusive mode (the Indian default) extracts `tax = amount − amount / (1 + rate)` per line and
reallocates the base pro-rata; the split is CGST+SGST for intra-state and IGST otherwise.

## 6 · Search

`search/SearchIndex` is a hand-rolled BM25 inverted index (k1 = 1.35, b = 0.72) over weighted
fields, with side indexes for prefix scans, deletion-variant fuzzy matching (edit distance 1,
or 2 for tokens ≥ 7 chars) and a trigram index for phone/code/email substring lookups.
Documents are tombstoned, never re-compacted in place. Every result carries microsecond timings
and telemetry (`engine`, `params`, `postings`, `vocabulary`, `strategy`) which the console prints.

## 7 · What is deliberately absent

* No ORM, no SQL, no external cache, no message bus, no container.
* No inline JavaScript anywhere (CSP `script-src 'self'`); fonts come from Google Fonts with
  full system fallbacks so the site is readable offline.
* No third-party analytics or trackers. The only telemetry is the server's own access log.
