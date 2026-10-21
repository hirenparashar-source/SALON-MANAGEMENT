# HTTP API

Base URL is the origin of the site. Everything is JSON unless the path says otherwise.
`GET /api/meta/routes` returns the live route inventory; `GET /api/health` the machine's vitals.

## Conventions

* **Errors** are `{"error": {"code","message","details","at"}}` with a sensible status:
  `400` validation, `401` unauthenticated, `402` payment declined, `403` wrong role,
  `404` not found, `409` conflict (slot taken, already billed), `429` rate limited (with
  `Retry-After`), `500` our fault.
* **Auth** is cookie based. Sign in with `POST /api/auth/login`; the response sets `sillage_sid`
  and `sillage_csrf` and returns the account. Send the CSRF cookie back as `X-CSRF-Token` on any
  non-GET.
* **Roles**: `GUEST < CLIENT < STYLIST < RECEPTION < MANAGER < OWNER`. Endpoints below note the
  minimum role; unmarked public endpoints are readable by anyone.
* **Money** is a decimal string or number in rupees, always two places. Dates are ISO.
* **Search** narrows itself to public kinds (`service`, `staff`, `look`, `product`) for guests, so
  a visitor can never enumerate the client book.

## Meta & bootstrap

| Method | Path | Min role | Notes |
|---|---|---|---|
| GET | `/api/health` | public | uptime, memory, request counters, store + index stats, open/closed |
| GET | `/api/bootstrap` | public | one call that hydrates a page: salon, menu, staff, looks, testimonials, loyalty, payment methods, open status, session, features, gateway, engine banner |
| GET | `/api/meta/routes` | STYLIST | the live route inventory |
| GET | `/api/meta/engine` | public | the suggestion rulebook with formulas and thresholds |

## Auth, accounts, profile

| Method | Path | Min role | Notes |
|---|---|---|---|
| POST | `/api/auth/register` | public | username, displayName, email, phone, password, `createCustomerRecord`; sets cookies |
| POST | `/api/auth/login` | public | timing-equalised; 423-equivalent lockout after 5 failures (429/401 with details) |
| POST | `/api/auth/guest` | public | guest session, optional `customerId` to link a record |
| POST | `/api/auth/logout` | any | clears the cookie |
| GET | `/api/auth/me` | public | the current actor, capabilities and preferences |
| POST | `/api/auth/password` | CLIENT | change own password; revokes other sessions |
| POST | `/api/auth/password/reset` | public | issues a token; in dev mode the token is echoed for testing |
| POST | `/api/auth/password/confirm` | public | token + new password |
| GET | `/api/auth/policy` | public | the password policy, evaluated client-side too |
| GET/DELETE | `/api/auth/sessions[/:id]` | CLIENT | list / revoke own sessions |
| POST | `/api/auth/sessions/revoke-all` | CLIENT | sign out everywhere |
| POST | `/api/auth/link-customer` | CLIENT | two-step: phone → desk code → link the client record |
| GET | `/api/auth/stats` | MANAGER | lockouts, sessions, password age |
| GET | `/api/accounts` | MANAGER | all accounts (never hashes) |
| POST | `/api/accounts/staff` | MANAGER | creates an account with a one-time password |
| PATCH | `/api/accounts/:id/role` `/active` | OWNER | role change / disable |
| GET/PATCH | `/api/profile` | CLIENT | account fields; `PATCH` sanitises |
| GET/PUT | `/api/profile/preferences` | CLIENT | accessibility + theme prefs, server-side copy |

## Catalogue & people

| Method | Path | Min role | Notes |
|---|---|---|---|
| GET | `/api/catalogue` | public | full menu; `publicOnly` applied for non-staff |
| GET | `/api/services[/:id]` | public | list + detail with variants, stylists, prep/aftercare |
| POST/PATCH/DELETE | `/api/services[/:id]` | STYLIST / MANAGER | CRUD; `PATCH .../featured` |
| GET/POST/PATCH/DELETE | `/api/categories[/:id]` | public / STYLIST | |
| GET/POST/PATCH/DELETE | `/api/looks[/:id]` | public / STYLIST | lookbook entries |
| GET/POST | `/api/testimonials` | public | public submissions go in *unapproved* |
| PATCH | `/api/testimonials/:id/moderate` | STYLIST | approve / feature |
| GET | `/api/staff[/:id]` | public | roster (contact details hidden from the public) |
| GET | `/api/staff/:id/week` | STYLIST | per-day capacity vs booked |
| POST/PATCH/DELETE | `/api/staff[/:id]` | MANAGER / OWNER | CRUD |
| POST/DELETE | `/api/staff/:id/time-off[/:offId]` | MANAGER | leave |

## Diary & bookings

| Method | Path | Min role | Notes |
|---|---|---|---|
| GET | `/api/availability` | public | `serviceId, variantId, staffId, date, days` → flat days with every free slot and who is free |
| GET | `/api/slots` | public | one day, optionally including unavailable reasons |
| POST | `/api/appointments` | public | desk bookings become CONFIRMED, online ones REQUESTED; creates/links the client by phone |
| GET | `/api/appointments` | STYLIST | `from,to,staffId,status` |
| GET | `/api/appointments/mine` | CLIENT | own bookings |
| GET | `/api/appointments/:id` | CLIENT* | owner or staff |
| PATCH | `/api/appointments/:id/status` | STYLIST | the status machine (`GET /api/booking/transitions/:status` lists legal moves) |
| POST | `/api/appointments/:id/reschedule` `/cancel` `/rate` | CLIENT* | guest-owned via session or staff |
| GET | `/api/diary` | STYLIST | the day board: one column per chair with load and appointments |
| GET | `/api/appointments/lookup?code&phone` | public | guest self-service; proven by code + mobile |
| POST | `/api/appointments/:id/deposit` | public | creates the bill and takes a partial payment, proof = code + mobile |
| POST | `/api/appointments/:id/release` | public | guest cancellation with the same proof |
| POST | `/api/booking/reminders` | STYLIST | queue due reminders (dry run) |
| GET | `/api/booking/stats` | STYLIST | |

## Bills, payments, receipts

| Method | Path | Min role | Notes |
|---|---|---|---|
| POST | `/api/invoices/quote` | any signed-in | prices a cart without writing anything; returns the full explain trace |
| POST | `/api/invoices` | STYLIST | issue a bill |
| POST | `/api/invoices/from-appointment/:id` | STYLIST | bill a completed visit |
| GET | `/api/invoices[/:id]` | STYLIST / owner | list with stats; detail view includes lines, tenders, tax split, receipt links |
| PATCH | `/api/invoices/:id/notes` | STYLIST | |
| POST | `/api/invoices/:id/void` | OWNER | keeps the number in the sequence |
| POST | `/api/payments` | owner of bill | `method` + tender fields; partial amounts allowed; 3-D Secure returns `AUTHENTICATION_REQUIRED` with an `intentId` and (test mode) the OTP; declines return 402 |
| POST | `/api/payments/intent[/:id/cancel]` | owner of bill | raw gateway intents |
| GET | `/api/payments/intent/:id` | owner of bill | |
| POST | `/api/payments/:invoiceId/refund` | MANAGER | partial or full; claws back loyalty points |
| GET | `/api/payments/methods` | public | enabled tenders |
| GET | `/api/gateway/console` `/events` `/intents` `/test-cards` | STYLIST | simulated PSP console; test cards incl. decline and 3DS numbers |
| GET | `/api/receipts/:id` | owner | JSON view |
| GET | `/api/receipts/:id/print` | owner | the A4/A5 print page |
| GET | `/api/receipts/:id/text` | owner | 44-column thermal layout |
| GET | `/api/receipts/:id/csv` `/lines.csv` | STYLIST | |
| GET | `/api/receipts/:id/verify?code=` | owner | verifies the HMAC seal |
| GET | `/api/receipts/verify?number&code` | public | verify from the paper receipt |
| GET | `/api/day-close?date` | RECEPTION | the Z-report |
| GET/POST | `/api/gift-cards[/:code]` | STYLIST / public(code) | issue, list, balance |

## Clients, loyalty, GDPR

| Method | Path | Min role | Notes |
|---|---|---|---|
| GET | `/api/customers` | STYLIST | `q` searches the index; otherwise paged by name |
| POST | `/api/customers` | STYLIST | |
| GET | `/api/customers/:id` | STYLIST | full profile: stats, history, bills, loyalty, favourites, consent |
| PATCH | `/api/customers/:id` | STYLIST | |
| DELETE/POST | `/api/customers/:id[/restore]` | STYLIST | archive / unarchive |
| GET | `/api/customers/:id/export` | STYLIST | right of access, everything we hold |
| POST | `/api/customers/:id/erase` | owner of record | right to erasure; keeps tax-mandated bills anonymised; writes an audit receipt |
| GET | `/api/customers/duplicates` `/birthdays` `/tags` | STYLIST | |
| POST | `/api/customers/import` | MANAGER | CSV with a header row |
| GET | `/api/loyalty` | public | scheme explainer |
| GET | `/api/loyalty/:customerId` | owner of record | points, tier, next tier |
| GET | `/api/loyalty/leaderboard` | STYLIST | |

## Shelf, suggestions, analytics

| Method | Path | Min role | Notes |
|---|---|---|---|
| GET/POST/PATCH/DELETE | `/api/products[/:id]` | RECEPTION | stock edits only via the ledger |
| POST | `/api/products/:id/stock` `/receive` | STYLIST | adjust / purchase; every change is a `StockMovement` |
| GET | `/api/products/:id/movements` `/low` | STYLIST | |
| GET | `/api/inventory/stats` | STYLIST | values, margins, categories |
| GET | `/api/suggestions` | STYLIST | the feed (operations first, then bundles) + per-client cards in `meta.clients` |
| GET | `/api/suggestions/today` `/bundles` `/operations` | STYLIST | |
| GET | `/api/suggestions/customer/:customerId` | STYLIST | |
| POST | `/api/suggestions/upsell` | any signed-in | live add-ons for a cart being built |
| GET | `/api/suggestions/engine` | public | the rulebook |
| GET | `/api/suggestions/matrix` `/demand` `/stats` `/states` | STYLIST | co-occurrence pairs, weekday-hour demand, state |
| POST | `/api/suggestions/:key/state` | STYLIST | done / dismissed / saved |
| GET | `/api/analytics/overview?range` | RECEPTION | 26 KPIs with deltas vs the previous window |
| GET | `/api/analytics/revenue|services|staff|channels|products` | RECEPTION | series and leaderboards |
| GET | `/api/analytics/retention` `/occupancy` `/forecast` | RECEPTION | RFM segments, heat grid, documented MA forecast |
| GET | `/api/analytics/tax[.csv]` | MANAGER | CGST/SGST working summary |

## Search, outbox, audit, data room, settings

| Method | Path | Min role | Notes |
|---|---|---|---|
| GET | `/api/search` | public* | `q,kind,limit,offset,sort,filter=attr:value,f_minPrice…`; guests limited to public kinds |
| GET | `/api/search/suggest` | public | autocomplete |
| GET | `/api/search/stats` `/`POST `/api/search/rebuild` | STYLIST | |
| GET | `/api/notifications[/:stats]` `/templates` | RECEPTION | the dry-run outbox |
| POST | `/api/notifications/:id/resend` | RECEPTION | |
| GET | `/api/audit[.csv]` `/summary` `/entity/:kind/:id` | MANAGER | who did what, with address and moment |
| GET | `/api/exports` | STYLIST | the export catalogue with live row counts |
| GET | `/api/export/:kind[.csv|/json]` | STYLIST | customers, appointments, invoices, products, services, staff, movements, giftcards, expenses, notifications, audit |
| POST | `/api/backup` | MANAGER | snapshot to `data/backups/` |
| GET | `/api/backups` | MANAGER | |
| POST | `/api/restore/:name` | OWNER | replays a snapshot and rebuilds the index |
| GET | `/api/storage` | STYLIST | disk use, collection counts, index stats |
| GET | `/api/integrity` | STYLIST | orphan and money-invariant checks |
| POST | `/api/purge` | MANAGER | prune intents, sent mail, expired sessions, audit overflow |
| POST | `/api/reset` | OWNER | wipe + reseed the demo salon |
| GET | `/api/settings` | public | public view; OWNER gets the full admin view |
| PATCH/PUT | `/api/settings` | OWNER | house rules; every change is audited |
| GET | `/api/settings/hours|loyalty|payments|features` | public | |

`*` = readable publicly, but the payload is narrowed for anonymous visitors.
