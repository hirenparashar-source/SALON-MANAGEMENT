# The house style

SILLAGE is set like a printed magazine, not like a dashboard. The reference is a Ludhiana maison's
own quarterly — paper, rules, folios and a display serif — and every decision below exists to keep
that illusion honest.

## Type

| Role | Face | Where |
|---|---|---|
| Display | **Bodoni Moda** (Didot/Georgia fallback) | masthead, headlines, prices on receipts, big numbers |
| Reading | **Jost** (Avenir Next/Segoe UI fallback) | body copy, UI, forms |
| Data | **IBM Plex Mono** (ui-monospace fallback) | every label, timestamp, money figure, code, badge |

Headlines run at `clamp()` sizes with tight leading and a hair of negative tracking; italic Bodoni
is the only ornament headlines get. Kickers are 0.6875rem mono at 0.34em tracking, uppercased, in
old gold, followed by a hairline rule that runs to the measure.

## Palette

Bone `#F4F1EA` paper `#EAE5DA` ink `#151310` soft-ink `#3A352E` muted `#7B7367`
old-gold `#9C7B3F` champagne `#C9A96A` bordeaux `#7A2E3A` verdigris `#4E6155` stone `#CFC8BA`.

Dark room: `#0E0D0B` / `#16150F` / `#EDE8DE` with champagne as the accent. High contrast mode
collapses to true black-on-white (or white-on-black) with 3px rules.

No gradients on surfaces, no glassmorphism, no purple, no emoji. Status is colour **plus** text,
always.

## Devices instead of shadows

* **Hairline rules** (1px at 16 % ink) divide; a 2px rule at full ink opens a section.
* **Dot leaders** turn the service menu into a wine list: name ……… price.
* **Folios** (`No. 01`, `02`, …) sit opposite every section head in oversized stone Bodoni.
* **Marginalia** — mono 0.6875rem notes in the side column — carry the asides a magazine would set
  in the margin.
* **Pull quotes** get a rule above and below and italic Bodoni at 1.5rem.
* **Cards** are borders, not shadows; the only elevation in the product is a modal and a toast.
* **The ticker** under the masthead is the closest thing to motion the public pages allow, and it
  pauses on hover and dies under reduced motion.

## Layout

A 1360px measure with `clamp()` gutters; asymmetric two-column splits (1.05fr / 1fr) rather than
equal thirds; the menu set in auto-fit columns of 320px minimum so a phone gets one column and a
laptop gets three. The console keeps a 15.5rem left rail that becomes an off-canvas drawer below
1080px.

## Imagery

Three photographs, generated for this house: the floor under north light, a colourist's hands
mid-pass, a bridal chignon from behind. They are desaturated 28 % by CSS and only regain colour on
hover — the page should read as paper first.

## Components worth naming

* `.dish` — one line of the wine-list menu.
* `.plate` — a framed photograph with a mono caption strip over a bottom gradient.
* `.leader .dots` — the dot leader itself.
* `.seal` — a boxed, letter-spaced receipt seal; also used for one-time codes.
* `.suggestion` — a left-ruled card showing score meter, rule chips and rationale.
* `.diary` — the console's chair grid: sticky hour column, one column per stylist, load bars in
  the header.
* `.heat` — the occupancy grid, colour-mixed from the accent so it inherits the theme.

## What we refuse

Rounded-2xl-everything, gradient buttons, glass panels, emoji as iconography, purple-to-pink
anything, cards that float, skeletons that shimmer, and any animation that does not correspond to
a real change of state.
