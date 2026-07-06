# LatteX — the fx gallery

Auto-generated visual references, captured by [BrewShot](https://github.com/supsup/BrewShot)
(the real-browser harness these tests run on) every full suite run. They are
**references, not goldens** — effects randomize, so frames differ run to run;
what a change here means is "the render changed," which is exactly what a
reviewer should look at.

Every image on this page is also a **test artifact**: the same captures carry
build-failing assertions (glyph boxes can't exceed 2× their SVG mid-animation,
triggers must produce changing frames, overlays must tear down on scroll).
The gallery is the receipts; the suite is the proof.

---

## The effects page

All specimens of the `\lx[fx.*]{…}` catalogue, one card each — enter effects
caught mid-play ([source page](effects.html)):

![The effects page](effects.png)

---

## Effects on film

Hover and click states a still can't show. Each GIF is clipped to its card and
loops forever.

### `fx.hover=glitch` — RGB-split flicker

![glitch on hover](fx-hover-glitch.gif)

### `fx.click=shatter` — glass shards in zero-g, then reassembly

![shatter on click](fx-click-shatter.gif)

### `fx.enter=wobble` — the placement-composed jiggle

The one that earns its keep: wobble rides `setPathDelta`, the
placement-composing transform path. If glyph placement composition ever
regresses (the "blob class"), this capture is where it shows first — and the
suite fails before your eyes get the chance.

![wobble on enter](fx-enter-wobble.gif)

---

## The first semantic effect

`fx.thread` — hover a variable and every occurrence of it lights up, driven by
the `data-lx-glyphmap` sidecar ([source page](thread-preview.html)):

![thread preview](thread-preview.png)

---

*Regenerate: `./gradlew test` with a local Chrome (the harness assume-skips
without one). Captures land beside their pages in this directory.*
