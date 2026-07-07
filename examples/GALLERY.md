# LatteX — the fx gallery

*(Looking for the math itself rather than the effects? The renderer tour is
[showcase.html](showcase.html) — every formula on it ratchet-locked.)*

Every effect in the `\lx[fx.*]{…}` catalogue, each isolated on a one-effect page
and captured as its own looping GIF via [BrewShot](https://github.com/supsup/BrewShot)'s
element-targeted `recordGifElement`. Each one **plays itself** — no hover or click needed.

**These images are for your eyes, not for machines to diff.** The effects randomize on
purpose — glitch's flicker, shatter's shard paths — so two runs never produce the same
pixels, and that's fine. The machines stand guard elsewhere: build-failing checks catch the
things wrong in *every* run (a glyph ballooning past 2× its equation, a hover that does
nothing, an overlay that survives scrolling away). So by the time an image reaches this page
it can't be *broken* — only *different*. Whether different is better is the call that stays human.

---

## The effects page

All specimens at a glance — enter effects caught mid-play ([source](effects.html)):

![The effects page](effects.png)

---

## The catalogue, in motion

Each GIF is clipped to its own equation and loops forever; the trigger is shown in its
`\lx[fx.*]` source.

### `fx.click=boom`

```
\lx[fx.click=boom]{ E = mc^2 }
```

![boom](boom.gif)

### `fx.hover=pulse`

```
\lx[fx.hover=pulse]{ \oint \vec{B}\cdot d\vec{l} }
```

![pulse](pulse.gif)

### `fx.enter=fade`

```
\lx[fx.enter=fade]{ a + b = c }
```

![fade](fade.gif)

### `fx.click=glow`

```
\lx[fx.click=glow]{ \phi = \frac{1+\sqrt5}{2} }
```

![glow](glow.gif)

### `fx.click=lightning`

```
\lx[fx.click=lightning]{ \nabla^2 \phi = 0 }
```

![lightning](lightning.gif)

### `fx.hover=storm`

```
\lx[fx.hover=storm]{ i\hbar\,\partial_t\psi }
```

![storm](storm.gif)

### `fx.enter=handscribe`

```
\lx[fx.enter=handscribe]{ e^{i\pi}+1=0 }
```

![handscribe](handscribe.gif)

### `fx.enter=hologram`

```
\lx[fx.enter=hologram]{ \psi(x,t) }
```

![hologram](hologram.gif)

### `fx.enter=neonsign`

```
\lx[fx.enter=neonsign]{ \int_a^b f\,dx }
```

![neonsign](neonsign.gif)

### `fx.enter=crystallize`

```
\lx[fx.enter=crystallize]{ \zeta(s)=\sum n^{-s} }
```

![crystallize](crystallize.gif)

### `fx.enter=blueprint`

```
\lx[fx.enter=blueprint]{ \frac{d}{dx}e^x=e^x }
```

![blueprint](blueprint.gif)

### `fx.enter=wobble`

```
\lx[fx.enter=wobble]{ x^2 + y^2 = r^2 }
```

![wobble](wobble.gif)

### `fx.enter=gravwell`

```
\lx[fx.enter=gravwell]{ \sum_{n=1}^\infty \frac1{n^2} }
```

![gravwell](gravwell.gif)

### `fx.enter=matrixrain`

```
\lx[fx.enter=matrixrain]{ \begin{pmatrix}a&b\\c&d\end{pmatrix} }
```

![matrixrain](matrixrain.gif)

### `fx.click=supernova`

```
\lx[fx.click=supernova]{ c = 3\times10^8 }
```

![supernova](supernova.gif)

### `fx.hover=diffusion`

```
\lx[fx.hover=diffusion]{ \partial_t u = D\nabla^2 u }
```

![diffusion](diffusion.gif)

### `fx.click=teleport`

```
\lx[fx.click=teleport]{ |\psi\rangle }
```

![teleport](teleport.gif)

### `fx.click=shatter`

```
\lx[fx.click=shatter]{ a^2-b^2=(a-b)(a+b) }
```

![shatter](shatter.gif)

### `fx.hover=glitch`

```
\lx[fx.hover=glitch]{ \nabla\cdot \vec{E}=\rho }
```

![glitch](glitch.gif)

### `fx.enter=sparkler`

```
\lx[fx.enter=sparkler]{ \gamma\approx 0.5772 }
```

![sparkler](sparkler.gif)

### `fx.enter=quantum`

```
\lx[fx.enter=quantum]{ \Delta x\,\Delta p\ge\hbar/2 }
```

![quantum](quantum.gif)

### `fx.click=typeset`

```
\lx[fx.click=typeset]{ \Gamma(n)=(n-1)! }
```

![typeset](typeset.gif)

### `fx.enter=constellation`

```
\lx[fx.enter=constellation]{ \pi\approx 3.14159 }
```

![constellation](constellation.gif)

---

## The first semantic effect

`fx.thread` — hover a variable and every occurrence lights up, driven by the
`data-lx-glyphmap` sidecar ([source page](thread-preview.html)):

![thread preview](thread-preview.png)

---

*Regenerate: capture with a local Chrome via BrewShot `recordGifElement` (see the fx-catalogue
capture harness). `gravwell` needs a synthetic glyph-click; `refraction`/`inkdrop` await the
pointer-stream (`recordGifStream`) path.*
