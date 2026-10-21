# The suggestion engine, rule by rule

`com.sillage.service.SuggestionEngine` produces every recommendation in this product. It contains
no model, no training step, no weights learned from data and no call to any external service.
It is arithmetic over the salon's own diary, and `GET /api/meta/engine` prints the whole rulebook —
inputs, formula and thresholds — so anyone can recompute a card by hand.

The engine banner, shown on every surface:
**Sillage Rule Engine v1 · deterministic (co-occurrence, Markov, RFM, thresholds)**.

## Corpus

The engine reads, per client: completed appointments (services, chair, timestamps), the invoice
lines behind them, retail purchases, ratings and cancellations. Nothing else. There is no
demographic or third-party data anywhere in the computation.

## The rules

| Rule | Technique | Inputs | Formula / thresholds |
|---|---|---|---|
| R-COOC-1 | Market basket | pairs of services sharing a visit, ≤3 services forward | support = pairs / visits; confidence = pairs / visits(from); **support ≥ 2, lift ≥ 1.15** |
| R-COOC-2 | Bundle lift | same pairs restricted to multi-service visits | **support ≥ 3, confidence ≥ 22 %, lift ≥ 1.3** |
| R-MARK-1 | First-order Markov | last service → next service transitions | P(next \| last) = transitions / occurrences(last); weight 0.28 |
| R-RFM-1 | Rebooking interval | median gap between a client's visits for the same service | suggest when `daysSince ≥ median × 0.9`; weight 0.30 |
| R-ROT-1 | Category rotation | service → category → next-likely category table | fixed editorial table, weight 0.16 |
| R-SEA-1 | Season & festival | month + festival keyword table (Diwali, wedding season, monsoon) | keyword match on month, weight 0.12 |
| R-CHA-1 | Chair affinity | client's preferred chair and that chair's free slots | only surfaces bookable slots, weight 0.10 |
| R-OCC-1 | Occasion | birthdays within 30 days | hard trigger, adds a "treat yourself" card |
| R-RET-1 | Rebook prompt | days since last completed visit vs the client's median gap | `≥ 1.2 × median` |
| R-WIN-1 | Win-back | days since last visit | **0.7 × median gap of their tier** |
| R-CHR-1 | Churn risk | days since last visit | **> 1.5 × median** ⇒ churn band; **> 2 ×** ⇒ high |
| R-UPS-1 | Point-of-sale attach | cart contents + co-occurrence + retail links | top 3 by lift not already in the cart |
| R-INV-1 | Reorder | stock, usage per service, 14-day forecast consumption | `stock − forecast ≤ reorderLevel` |
| R-STF-1 | Staffing | weekday-hour booking counts over 56 days | top 3 (day, hour) cells above `mean + σ` |
| R-PRC-1 | Price position | service price vs category median | flag when `price < 0.8 × median` and bookings are high |
| R-LOY-1 | Tier proximity | lifetime spend vs the next tier's threshold | suggest when `≥ 80 %` of the way there |
| R-HSE-1 | Housekeeping | consent flags, duplicate records, expired gift cards | deterministic list checks |
| R-HSE-2 | Receipt follow-up | bills settled in the last 7 days without a rating | ask once |

## Scoring and filters

A client card's score is a weighted sum of the applicable rule scores
(`0.42 co-occurrence + 0.28 Markov + 0.30 rebook + 0.16 rotation + 0.12 season + 0.10 chair`,
renormalised over the rules that fired), then two hard filters remove anything:

* priced above **1.8 × the client's average ticket**, or
* suggested within the rule's **cooldown** (a suggestion already dismissed or done in the last
  30 days is not repeated).

Bundle scores avoid the 1.0 ceiling: `min(0.99, confidence × min(1, (lift − 1)/2) + 0.25)`, so a
60 %-confidence, 4×-lift pair (0.85) still outranks a 40 %-confidence, 1.3×-lift one (0.29).

## Why this and not a model

Three reasons, in the order the owner gave them: a salon can be sued over a wrong assumption about
a person but not over a count; a count can be explained to a client at the desk ("you usually come
every seven weeks, it has been nine"); and a count can be recomputed from the diary by anyone with
a spreadsheet, which is the only audit trail a small business can actually read. The console shows,
on every card, the rule ids, the inputs and the rationale sentence built from those same numbers —
and `/api/suggestions/engine` publishes the table above verbatim.
