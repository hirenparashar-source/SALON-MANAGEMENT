# The house style

SILLAGE is set like a printed magazine, not like a dashboard. The reference is a Ludhiana maison's
own quarterly — paper, rules, folios and a display serif — and every decision below exists to keep
that illusion honest.

## Type

| Role | Face | Where |
|---|---|---|
| Display | **Lora** (Baskerville/Palatino/Garamond/Georgia fallback) | masthead, headlines, prices on receipts, big numbers |
| Reading | **Jost** (Avenir Next/Segoe UI fallback) | body copy, UI, forms |
| Data | **IBM Plex Mono** (ui-monospace fallback) | every label, timestamp, money figure, code, badge |

Headlines run at `clamp()` sizes with tight leading and a hair of negative tracking; an italic
word in the display face is the only ornament they get. Lora replaced the Didone that shipped
first: at headline sizes a high-contrast face reads as harsh and over-heavy on cream, and a
lower-contrast transitional serif sets softer without losing authority. The italic accent keeps
the heading's exact family and weight, so only its colour differs.

Body copy is set at 400. It shipped at 300, which is too thin to read comfortably at 1rem on a
cream ground; the extra weight costs nothing and lifts the whole page.

Kickers are 0.6875rem mono at 0.34em tracking, uppercased, in a mid-tone taupe-brown, followed by
a hairline rule that runs to the measure. They were old gold once — pretty at 3.5:1, but it is
micro-copy people actually need to read, and the taupe clears AA at 5.6:1.

## Palette

Bone `#F4F1EA` paper `#EAE5DA` card `#FBF9F5`.

There is no black in the palette. The ink is a deep espresso `#362511` and the greys below it are
warm browns all the way down: soft-ink `#4A3826`, muted `#6E5D47`, faint `#8B7A62`, stone
`#CFC8BA`. Chestnut `#5B3A21` (deep `#452B18` on hover) carries the primary buttons and the
outline buttons; terracotta `#A8552E` is the accent word. Old-gold `#9C7B3F` champagne `#C9A96A`
bordeaux `#7A2E3A` verdigris `#4E6155` remain for rules, seals, charts and status.

Every one of those substitutions also moved a contrast ratio up, not down: muted body copy 4.2:1 →
5.6:1, kickers 3.4:1 → 5.6:1, placeholders 2.4:1 → 3.7:1, and chestnut-on-bone buttons land at
9:1. Softer is not the same as fainter.

Dark room: `#0E0D0B` / `#16150F` / `#EDE8DE` with champagne as the accent; the brown family
lightens there (chestnut `#B07C4F`, terracotta `#D98A5F`) so buttons still read against the dark
ground. High contrast mode collapses to true black-on-white (or white-on-black) with 3px rules, and
gets its own darker chestnut `#33200F` and terracotta `#7A3410`.

No gradients on surfaces, no glassmorphism, no purple, no emoji. Status is colour **plus** text,
always.

## Devices instead of shadows

* **Hairline rules** (1px at 18 % espresso) divide; a 2px rule at full ink opens a section.
* **Dot leaders** turn the service menu into a wine list: name ……… price.
* **Folios** (`No. 01`, `02`, …) sit opposite every section head in oversized stone Lora.
* **Marginalia** — mono 0.6875rem notes in the side column — carry the asides a magazine would set
  in the margin.
* **Pull quotes** get a rule above and below and italic Lora at 1.5rem.
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
