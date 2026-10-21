# Reading &amp; access

Every surface — the magazine, the booking book and the console — shares one accessibility panel
(`?` on the keyboard, or the **Access** button). Its state lives in `localStorage` under
`sillage.prefs.v1`, is applied to `<html>` as `data-*` attributes before first paint, and, once you
sign in, is also stored server-side (`PUT /api/profile/preferences`) so it follows you between
devices.

| Control | What it does | Implementation |
|---|---|---|
| Text size | 85 % – 150 % in 5 % steps | sets `--fs`; `html { font-size: calc(16px * var(--fs)) }` so every rem scales |
| Appearance | Bone (light) / Ink (dark) | `data-theme` swaps the token set, including a dark palette that is not just inverted |
| Contrast | House / High | `data-contrast=high` pushes fg/bg to the extremes and thickens rules and focus outlines |
| Motion | Full / Reduced | `data-motion=reduce` zeroes transitions/animations and disables scroll smoothing and the ticker |
| Reading face | Jost / Dyslexia-friendly | `data-font=dyslexia` swaps to a Verdana stack with added letter- and word-spacing |
| Links | Colour only / Underlined | `data-underline=on` underlines every inline link (WCAG 1.4.1) |
| Text spacing | House / Relaxed | `data-spacing=relaxed` applies the WCAG 1.4.12 metrics: line-height 1.8, letter-spacing .06em, word-spacing .18em |
| Touch targets | Normal / Large | `data-targets=large` raises the minimum control height to 48 px |
| Spoken feedback | On / Off | gates the `aria-live` region used for toasts, search result counts and step changes |

Beyond the panel:

* **Structure** — one `<h1>` per page, sections numbered like a magazine (`No. 01`…), a skip link to
  `#main`, landmark roles, and every interactive control reachable by Tab in DOM order.
* **Focus** — a visible `:focus-visible` ring in old gold (3 px in high contrast), and a focus trap
  inside modals with `Esc` to close and focus returned to the opener.
* **Keyboard** — the console maps `?` (access panel), `/` (global search), `g` then `d/c/p/i/s`
  (dashboard, diary, POS, invoices, suggestions); the booking book's slot grid is arrow-key
  navigable and each slot is a real `<button>` with `aria-pressed`.
* **Announcements** — a single polite `role="status"` region; errors are announced assertively.
  Toasts mirror their text into it.
* **Colour** — status hues are chosen to remain distinguishable under deuteranopia and protanopia
  (bordeaux vs verdigris vs gold, never red/green alone), and every badge pairs colour with text.
* **Forms** — labels are always present (`<label for>`), errors are text next to the field and
  mirrored into `role="alert"`, and no field relies on placeholder alone.
* **Images** — descriptive `alt` on the three photographs; decorative swatches and dot leaders are
  `aria-hidden`.
* **Reduced motion by OS** — on first visit, `prefers-reduced-motion: reduce` and
  `prefers-color-scheme: dark` are honoured *only if* you have not chosen a preference yourself.
* **Print** — the receipt page is a real print stylesheet (A5/A4, no chrome, page margins) and the
  booking confirmation offers an `.ics` file rather than a fake "add to calendar" button.

The panel itself is a modal built from the same primitives, is fully keyboard operable, and its
"Restore defaults" control returns every token to the house setting.
