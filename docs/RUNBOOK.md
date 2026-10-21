# Runbook

## Start, stop, health

```bash
./run.sh                      # build if needed, seed on first boot, listen on :8080
./run.sh --port=9000 --dev    # another port, verbose logging
SILLAGE_DATA=/srv/sillage ./run.sh
curl -s localhost:8080/api/health | jq .status      # "ok"
```

The process writes its own access log to stdout:
`GET /api/invoices -> 200 (9.4 ms, 127.0.0.1, req thktckmoQiM)`. Every response also carries
`X-Request-Id`; quote it when debugging. `Ctrl-C` flushes the store and stops cleanly.

## Data layout

```
data/
  settings.json  services.json  customers.json  appointments.json  invoices.json
  products.json  stock_movements.json  users.json  credentials.json  sessions.json
  audit_events.json  ...               # one file per collection, pretty-printed JSON
  receipt.key                          # HMAC key for receipt seals (never ship this)
  backups/sillage-<ts>.json            # snapshots
```

Writes are debounced 250 ms and atomic (temp file + rename). You may `cat` any file at any time;
you should never edit one while the server runs (stop it, edit, start it).

## Backup & restore

```bash
curl -X POST -b cookies.json localhost:8080/api/backup -d '{"label":"before-upgrade"}'
curl -b cookies.json localhost:8080/api/backups
curl -X POST -b cookies.json localhost:8080/api/restore/sillage-20261021-104327.json
```

or in the console: **Data room → Snapshots**. A restore replaces every collection and rebuilds the
search index; it requires the OWNER role and is written to the audit trail.

A filesystem copy is equally valid: stop the server, `cp -a data data.copy`, start it.

## Exports

**Data room** lists every collection with its live row count; each row downloads CSV. From the CLI:

```bash
curl -b cookies.json -OJ localhost:8080/api/export/customers.csv
curl -b cookies.json -OJ localhost:8080/api/export/invoices.csv   # GST-ready columns
curl -b cookies.json -OJ localhost:8080/api/audit.csv             # MANAGER and above
```

## Reset, reseed, empty start

```bash
./run.sh reset          # wipe ./data, reseed the demo salon
./run.sh --no-seed      # start with an empty salon (you become the first OWNER via the console)
```

The seed is deterministic (`seed 20261021`): the same 603 bookings, 442 balanced invoices, 30
clients and 14 products every time, so demos and screenshots agree.

## Upgrading

1. `POST /api/backup`.
2. Stop the server; `git pull` (or copy the new tree).
3. `./build.sh && ./run.sh`.
4. Check `/api/health` and **Data room → integrity**.

There is no schema migration step: collections are additive JSON, and unknown fields are ignored
on read. If a future release ever needs one, it will ship a `data/upgrade-<n>.md` note.

## Troubleshooting

| Symptom | First things to check |
|---|---|
| Port in use | `--port=…`, or `lsof -i :8080`; the banner prints the bound port |
| 403 on everything | you are a guest — sign in; roles are listed on the sign-in screen |
| 429 on sign-in | six attempts per minute per address; wait 60 s |
| Search finds nothing | `POST /api/search/rebuild` (STYLIST); check `/api/search/stats` |
| Receipt fails to verify | the code is case-insensitive but must be the 12 chars under "Receipt seal"; verify also works by number via `/api/receipts/verify` |
| Fonts look wrong offline | the site falls back to Didot/Georgia, Avenir/Segoe UI and ui-monospace by design |
| Data dir not writable | the banner says so at boot; point `SILLAGE_DATA` somewhere writable |

## Security notes for a real deployment

* Put TLS in front (any reverse proxy) and set `SILLAGE_SECURE_COOKIES=true`.
* If the proxy terminates TLS, keep `SILLAGE_TRUST_PROXY=true`; if you expose the port directly,
  set it `false` so client IPs cannot be spoofed for the rate limiter.
* `data/receipt.key` and `data/backups/` are the two things worth encrypting at rest.
* Rotate staff passwords from **Accounts**; the audit trail records who changed what and when.
