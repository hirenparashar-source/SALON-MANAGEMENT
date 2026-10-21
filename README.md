# SILLAGE

**Maison de coiffure &amp; beauté · Ludhiana · est. 1987**

A complete salon-management suite — booking diary, point of sale, client book, inventory,
loyalty, analytics, a rule-based suggestion engine, payments, GST receipting, audit trail,
exports and backups — written as **pure Java with zero dependencies**, served by one small
program that also publishes an editorial, magazine-styled front end.

No Spring. No Hibernate. No Jackson. No database server. No build tool required.
One JDK, one folder, `./run.sh`.

```
┌───────────────────────────────────────────────────────────────────────┐
│   S I L L A G E   ·   maison de coiffure & beauté                      │
│   salon management suite · pure Java · zero dependencies               │
└───────────────────────────────────────────────────────────────────────┘

  Landing page   http://localhost:8080/          the public magazine
  Public booking http://localhost:8080/book     five steps, no account needed
  Studio console http://localhost:8080/studio   the back office (role aware)
  API health     http://localhost:8080/api/health
```

---

## 1 · Run it

Requirements: **JDK 17 or newer** (`javac`, `java`) and nothing else. Not even a network.

```bash
git clone <this repo> && cd SALON-MANAGEMENT
./run.sh                 # builds on first run, seeds the demo salon, listens on :8080
```

That is the whole installation. Other shapes:

```bash
./run.sh test            # 48 checks against the real services
./run.sh reset           # wipe ./data and reseed the demo salon
./run.sh --port=9000     # another port (see ./run.sh --help for all flags)
./build.sh jar           # build/sillage.jar → java -jar build/sillage.jar
make test | make run | make jar | make clean
docker build -t sillage . && docker run --rm -p 8080:8080 -v sillage-data:/app/data sillage
```

Demo sign-ins (all optional — the public pages work with no account at all):

| Role    | Username    | Password        | Sees                                                     |
|---------|-------------|-----------------|----------------------------------------------------------|
| Owner   | `meher`     | `Sillage#2026`  | everything, incl. settings, accounts, audit, data room   |
| Manager | `frontdesk` | `FrontDesk#2026`| refunds, exports, day close, outbox, integrity            |
| Stylist | `ishaan`    | `Colour#2026`   | the floor, their chair, clients, suggestions              |
| Client  | `ritika`    | `Ritika#2026`   | only their own record, bookings, bills, loyalty           |

Configuration is by environment (`SILLAGE_PORT`, `SILLAGE_HOST`, `SILLAGE_DATA`,
`SILLAGE_WEB`, `SILLAGE_LOG`, `SILLAGE_SEED`, `SILLAGE_DEV`, `SILLAGE_SECURE_COOKIES`,
`SILLAGE_TRUST_PROXY`) or by flag (`--port`, `--host`, `--data`, `--web`, `--reset`,
`--no-seed`, `--dev`, `--quiet`). Data lives in `./data` as plain JSON files.

---

## 2 · What is inside

### The three surfaces
* **`web/index.html`** — the public magazine: a running ticker, a wine-list index of services
  with dot leaders and live search, the chairs, the lookbook, membership tiers, testimonials
  you can leave, house rules, hours and a receipt verifier.
* **`web/book.html`** — the booking book: service → chair &amp; day → time → details → confirm,
  with an optional deposit through the simulated gateway (3-D Secure included), an `.ics`
  download, and self-service lookup / cancellation by booking code + mobile.
* **`web/studio.html`** — the console: dashboard, diary (one column per chair), till, client
  book with GDPR export &amp; erasure, the index, the shelf with an immutable stock ledger,
  bills &amp; receipts, day close, the suggestion engine and its published rulebook, analytics
  with a forecast that explains itself, search playground with BM25 telemetry, outbox, audit
  trail, data room (exports, snapshots, restore, integrity), settings and accounts.

### The engine room (`src/main/java/com/sillage`)
| Package     | What it holds |
|-------------|---------------|
| `core`      | JSON parser/serialiser and reflection mapper, money (BigDecimal, scale 2), logging, errors, config |
| `data`      | `Repo<T>` with secondary indices and read/write locks; atomic file writes; debounced `DataStore` |
| `search`    | In-process BM25 inverted index: prefix + fuzzy + substring side indexes, facets, filters, ranges, µs telemetry |
| `model`     | Immutable records — the persistence *and* transport types (24 of them) |
| `security`  | PBKDF2-HMAC-SHA256 password hashing, sessions, rate limiting, validation, audit log |
| `web`       | JDK `com.sun.net.httpserver` server, router, static files, security headers, editorial error pages |
| `service`   | 19 services: booking, pricing (GST), invoicing, gateway, loyalty, inventory, suggestions, analytics, auth, data, … |
| `api`       | The 181-route HTTP surface, built by hand from the services |
| `seed`      | A deterministic 120-day trading history: 600+ bookings, 440+ balanced invoices, 30 clients |

### Money that agrees with itself
`subtotal` counts only SERVICE/PRODUCT/ADDON lines;
`base = subtotal − discount − loyalty`; `total = base + tax + gratuity + roundOff`;
gratuity and loyalty lines are display-only. Tax-inclusive mode extracts the tax and splits it
CGST/SGST (or IGST). An integrity check in the console re-derives every bill from its own lines.

### Suggestions without a model
`SuggestionEngine` is a set of **published, numbered rules** over counts — market-basket
support/confidence/lift, a first-order Markov chain over service sequences, median rebooking
intervals, category rotation, seasonality, chair affinity, churn bands, reorder points and a
weekday-hour demand curve. Every suggestion carries the rules that produced it and a rationale
sentence built from the same numbers; `/api/meta/engine` prints the whole rulebook with formulas
and thresholds. The analytics forecast is likewise a documented weekday-weighted moving average.

### Security posture
PBKDF2 (210 000 rounds, per-account salt), timing-equalised login with a decoy hash, lockout after
five failures, opaque 32-byte session tokens stored as SHA-256 digests, CSRF tokens, role checks on
every state-changing route, rate limits per surface, card numbers never stored (Luhn → brand →
HMAC token + last four), CVV never touched, receipts sealed with HMAC so paper bills verify
against the book, audit trail with severity-weighted pruning, and a strict Content-Security-Policy
(`default-src 'self'`, no inline scripts) on every response.

---

## 3 · The front end, in one paragraph

Set in **Lora** (display), **Jost** (reading) and **IBM Plex Mono** (every number, label and
timestamp), on a palette of bone, paper, espresso ink, chestnut, terracotta, old gold, champagne,
bordeaux, verdigris and stone —
hairline rules instead of shadows, dot leaders instead of cards, folios and marginalia instead of
badges-for-everything. An accessibility panel (font scale 85–150 %, high contrast, reduced motion,
dyslexia-friendly face, underlined links, WCAG 1.4.12 text spacing, large targets, spoken
announcements) applies instantly, persists on the device and, once signed in, on the server.

## 4 · Read next

* [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) — how a request moves through the machine
* [`docs/API.md`](docs/API.md) — the HTTP surface, endpoint by endpoint
* [`docs/SUGGESTION-ENGINE.md`](docs/SUGGESTION-ENGINE.md) — the rulebook, rule by rule
* [`docs/ACCESSIBILITY.md`](docs/ACCESSIBILITY.md) — what the panel does and why
* [`docs/RUNBOOK.md`](docs/RUNBOOK.md) — backups, restores, resets, upgrades, troubleshooting

## 5 · Licence &amp; provenance

Everything here is original work: the code, the copy, the seed salon and the photography prompts.
Fonts are served from Google Fonts with system fallbacks; the demo imagery was generated for this
repository.
