/* LatteX fx runtime — OPTIONAL. Reads data-lx-fx-* on .lx-math wrappers and animates the
   \lx effects. Self-contained, no deps, CSP-friendly (one external script). The math renders
   without this; include it (+ lattex-fx.css) only if you want the effects. */
(function () {
  // FIRST ACT: stamp the root marker the stylesheet's enter-hide rules key on
  // (html.lx-fx ... { opacity: 0 }). CSS loaded without this runtime running
  // then hides nothing — the math shows plainly with effects inert, instead of
  // the CSS-only-invisibility hole where enter-effect equations vanish forever.
  document.documentElement.classList.add('lx-fx');

  // CSS-keyframe effect vocabulary — mirrors the Effect enum's keyframe half
  // (boom|pulse|fade|glow|none). 'lightning' and 'storm' are special-cased
  // below: they are page-side body overlays, NOT keyframes on the element.
  var VOCAB = { boom: 1, pulse: 1, fade: 1, glow: 1, glitch: 1, none: 1 };
  var reduced = window.matchMedia
    && window.matchMedia('(prefers-reduced-motion: reduce)').matches;

  // Resolve the strike/halo colour: the author's fx.glow-color if set (and not
  // the literal 'currentColor', which canvas can't resolve), else the element's
  // computed ink colour so it matches the on-page glyphs.
  function resolveColor(el) {
    var c = (el.style.getPropertyValue('--lx-glow-color') || '').trim();
    if (c && c.toLowerCase() !== 'currentcolor') { return c; }
    var ink = getComputedStyle(el).color;
    return ink || '#e6a24c';
  }

  // ---- placement-composing path transforms ---------------------------------
  // Every glyph <path> carries its PLACEMENT as an SVG transform ATTRIBUTE
  // ('translate(tx ty) scale(s -s)': font design units → user units). The CSS
  // transform PROPERTY replaces that attribute wholesale, so a per-path effect
  // that sets style.transform naively strips the placement and re-renders the
  // glyph as a font-unit-sized blob (Charles's smoke, 2026-07-06). Per-path
  // effects therefore express motion as a USER-SPACE delta and COMPOSE it in
  // front of the placement via setPathDelta; pivotScaleDelta builds a scale
  // about the glyph's own user-space centre (replacing the transform-box
  // pattern, which suffers the same stomp). clearPathDelta restores the
  // attribute's rule. Paths without a placement attribute pass deltas through.
  function placement(p) {
    if (p.__lxPlace === undefined) {
      var m = /translate\(\s*([-\d.eE]+)[\s,]+([-\d.eE]+)\s*\)\s*scale\(\s*([-\d.eE]+)(?:[\s,]+([-\d.eE]+))?\s*\)/
        .exec(p.getAttribute('transform') || '');
      p.__lxPlace = m ? {
        css: 'translate(' + m[1] + 'px,' + m[2] + 'px) scale(' + m[3] + ',' + (m[4] || m[3]) + ')',
        tx: parseFloat(m[1]), ty: parseFloat(m[2]),
        sx: parseFloat(m[3]), sy: parseFloat(m[4] || m[3])
      } : null;
    }
    return p.__lxPlace;
  }
  function setPathDelta(p, deltaCss) {
    // The SVG transform ATTRIBUTE applies about the user-space origin (0,0);
    // the CSS transform PROPERTY applies about transform-origin, which for SVG
    // defaults to 50% 50% OF THE VIEWBOX — replaying the placement through CSS
    // without pinning the origin scales the equation about its centre and
    // scrunches every glyph into a bar (Charles's smoke, round 2). Pin it.
    p.style.transformOrigin = '0px 0px';
    var place = placement(p);
    p.style.transform = place ? deltaCss + ' ' + place.css : deltaCss;
  }
  function clearPathDelta(p) {
    p.style.transform = '';
    p.style.transformOrigin = '';
  }
  /** The glyph's centre in USER space (local bbox pushed through its placement). */
  function userCentre(p) {
    var b;
    try { b = p.getBBox(); } catch (e) { return null; }
    var cx = b.x + b.width / 2, cy = b.y + b.height / 2;
    var place = placement(p);
    if (!place) { return { x: cx, y: cy }; }
    return { x: place.tx + place.sx * cx, y: place.ty + place.sy * cy };
  }
  /** A scale-about-the-glyph's-own-centre delta (user space). */
  function pivotScaleDelta(p, k) {
    var c = userCentre(p);
    if (!c) { return 'scale(' + k + ')'; }
    return 'translate(' + c.x.toFixed(2) + 'px,' + c.y.toFixed(2) + 'px) scale(' + k
      + ') translate(' + (-c.x).toFixed(2) + 'px,' + (-c.y).toFixed(2) + 'px)';
  }

  // ---- scroll-killable shows ------------------------------------------------
  // Overlay effects are FIXED-position bodies anchored to the equation's
  // trigger-time rect — scroll the page and the show plays over the wrong
  // content (bolts strike a phantom, shards float over prose, a spotlight
  // darkens nothing: Charles's smoke sweep, 2026-07-06). scrollKillable wraps
  // an effect's teardown into an idempotent `die()` that ALSO fires on the
  // first scroll: the normal end and the scroll-abort share one exit, pending
  // frames/timers check die.dead() and park. Passive + capture so it hears
  // scrolls in any container and never blocks the scroll itself.
  function scrollKillable(teardown) {
    var dead = false;
    // Baseline the viewport at arming: browsers fire spurious/restoration
    // 'scroll' events at page load (killing fx.enter shows at birth — Charles:
    // "sparkler seems to not always load"), so only a REAL movement of the
    // viewport (>4px from the position the show started at) ends the show.
    var x0 = window.pageXOffset, y0 = window.pageYOffset;
    function onScroll() {
      if (Math.abs(window.pageXOffset - x0) < 4
          && Math.abs(window.pageYOffset - y0) < 4) { return; }
      die();
    }
    function die() {
      if (dead) { return; }
      dead = true;
      window.removeEventListener('scroll', onScroll, true);
      teardown();
    }
    die.dead = function () { return dead; };
    window.addEventListener('scroll', onScroll, { capture: true, passive: true });
    return die;
  }

  // A jagged polyline from (x0,y0) to (x1,y1): interior points are pushed off
  // the straight line by a random perpendicular jitter, so every strike differs.
  function jagged(x0, y0, x1, y1, segs, jitter) {
    var dx = x1 - x0, dy = y1 - y0;
    var len = Math.sqrt(dx * dx + dy * dy) || 1;
    var nx = -dy / len, ny = dx / len; // unit normal
    var pts = [];
    for (var i = 0; i <= segs; i++) {
      var t = i / segs;
      var x = x0 + dx * t, y = y0 + dy * t;
      if (i > 0 && i < segs) {
        var off = (Math.random() * 2 - 1) * jitter;
        x += nx * off; y += ny * off;
      }
      pts.push([x, y]);
    }
    return pts;
  }

  function stroke(ctx, pts, upto) {
    var n = Math.max(2, Math.min(pts.length, upto || pts.length));
    if (n < 2) { return; }
    ctx.beginPath();
    ctx.moveTo(pts[0][0], pts[0][1]);
    for (var i = 1; i < n; i++) { ctx.lineTo(pts[i][0], pts[i][1]); }
    ctx.stroke();
  }

  // The confluence: two rivers of lightning arc IN from the left- and right-mid
  // viewport edges and converge on the element's centre, flash, then fade. A
  // lazily-created, pointer-events-none body overlay — never inside the <svg>.
  function lightning(el) {
    var r = el.getBoundingClientRect();
    var cx = r.left + r.width / 2, cy = r.top + r.height / 2;
    var vw = window.innerWidth, vh = window.innerHeight;
    var color = resolveColor(el);
    var sources = [[0, vh / 2], [vw, vh / 2]]; // left-mid + right-mid edges

    var canvas = document.createElement('canvas');
    var dpr = window.devicePixelRatio || 1;
    canvas.width = Math.round(vw * dpr);
    canvas.height = Math.round(vh * dpr);
    canvas.style.cssText = 'position:fixed;left:0;top:0;width:100vw;height:100vh;'
      + 'pointer-events:none;z-index:2147483647;';
    document.body.appendChild(canvas);
    var ctx = canvas.getContext('2d');
    ctx.scale(dpr, dpr);

    // Reduced motion: one faint static arc from each side, no flicker, no flash.
    if (reduced) {
      ctx.globalAlpha = 0.45;
      ctx.strokeStyle = color;
      ctx.lineWidth = 2;
      sources.forEach(function (s) { stroke(ctx, jagged(s[0], s[1], cx, cy, 8, 6)); });
      setTimeout(function () { canvas.remove(); }, 400);
      return;
    }

    var DRAW = 180, HOLD = 90, FADE = 350;
    function drawBolts(progress) {
      ctx.clearRect(0, 0, vw, vh);
      ctx.strokeStyle = color;
      ctx.shadowColor = color;
      ctx.shadowBlur = 12;
      sources.forEach(function (s) {
        for (var k = 0; k < 2; k++) { // 2 forked bolts per side
          var pts = jagged(s[0], s[1], cx, cy, 12, 18 + k * 10);
          ctx.lineWidth = 2 + k;
          ctx.globalAlpha = 0.9;
          stroke(ctx, pts, Math.floor(pts.length * progress));
        }
      });
      if (progress > 0.85) { // bright convergence flash blob
        var rad = 10 + Math.random() * 6;
        var g = ctx.createRadialGradient(cx, cy, 0, cx, cy, rad);
        g.addColorStop(0, color);
        g.addColorStop(1, 'rgba(0,0,0,0)');
        ctx.globalAlpha = 1;
        ctx.fillStyle = g;
        ctx.beginPath(); ctx.arc(cx, cy, rad, 0, Math.PI * 2); ctx.fill();
      }
    }

    // The bolts converge on where the equation WAS at trigger time (fixed
    // overlay) — if the page scrolls the anchor is gone, so end the show
    // immediately rather than strike a phantom (Charles, 2026-07-06).
    var die = scrollKillable(function () { canvas.remove(); });

    var t0 = performance.now();
    function frame(now) {
      if (die.dead()) { return; }
      var e = now - t0;
      if (e < DRAW) { drawBolts(e / DRAW); requestAnimationFrame(frame); }        // draw in + flicker
      else if (e < DRAW + HOLD) { drawBolts(1); requestAnimationFrame(frame); }   // hold, still re-jagging
      else if (e < DRAW + HOLD + FADE) {                                            // fade the whole overlay
        canvas.style.opacity = String(1 - (e - DRAW - HOLD) / FADE);
        requestAnimationFrame(frame);
      } else { die(); }
    }
    requestAnimationFrame(frame);
  }

  // NIGHT LIGHTNING. Reuses the bolt code, but first drops the whole scene to
  // near-black behind a radial "spotlight" that stays transparent over the
  // target (so the equation stays lit), converges bolts on the centre, and on
  // EACH strike FLASHES the dark backdrop bright — like lightning at night —
  // then restores the page. The darken backdrop, the white flash veil and the
  // bolt canvas are three SEPARATE pointer-events-none body overlays; nothing
  // is ever written into the element's <svg> (the containment contract holds).
  function storm(el) {
    var r = el.getBoundingClientRect();
    var cx = r.left + r.width / 2, cy = r.top + r.height / 2;
    var vw = window.innerWidth, vh = window.innerHeight;
    var color = resolveColor(el);
    var sources = [[0, vh / 2], [vw, vh / 2], [cx, 0]]; // left, right, top edges
    // Spotlight radius: clears the element plus a soft margin.
    var spot = Math.max(r.width, r.height) / 2 + 60;

    // (1) DARKEN — a fixed radial backdrop: transparent over the element,
    // near-black at the edges. A CSS var drives the edge darkness so a strike
    // can momentarily lift it. Fades in fast (~150ms).
    var backdrop = document.createElement('div');
    backdrop.style.cssText = 'position:fixed;inset:0;pointer-events:none;'
      + 'z-index:2147483645;opacity:0;transition:opacity 150ms ease;background:'
      + 'radial-gradient(circle ' + spot + 'px at ' + cx + 'px ' + cy + 'px,'
      + ' rgba(2,4,9,0) 0%, rgba(2,4,9,0) 58%,'
      + ' rgba(2,4,9,var(--lx-dark,0.94)) 100%);';
    document.body.appendChild(backdrop);

    // (2) FLASH VEIL — a whitish layer brightest in the dark surround (a
    // transparent hole over the element), pulsed on each strike so the night
    // "lights up" without washing out the spotlit equation.
    var flash = document.createElement('div');
    flash.setAttribute('data-lx-fx-overlay', 'lightning');
    flash.style.cssText = 'position:fixed;inset:0;pointer-events:none;'
      + 'z-index:2147483646;opacity:0;transition:opacity 70ms ease;background:'
      + 'radial-gradient(circle ' + (spot + 40) + 'px at ' + cx + 'px ' + cy + 'px,'
      + ' rgba(226,238,255,0) 0%, rgba(226,238,255,0) 52%,'
      + ' rgba(226,238,255,0.6) 100%);';
    document.body.appendChild(flash);

    // The target GLOWS HOTTER as bolts charge into it: a heat level 0..1 maps
    // to a growing coloured halo + a white-hot core, warming toward white as
    // it peaks. Biased to the author's glow-color if set, else a heated amber.
    var authored = (el.style.getPropertyValue('--lx-glow-color') || '').trim();
    var heatTint = (authored && authored.toLowerCase() !== 'currentcolor')
      ? color : '#ffb24d';
    // Bolts read as LIGHT, not dark ink — a glowing electric strike against the
    // night. Electric blue-white by default, or the author's glow-color if set;
    // the halo (shadow) glows too so they blaze rather than draw as black cracks.
    var boltColor = (authored && authored.toLowerCase() !== 'currentcolor')
      ? authored : '#eaf3ff';
    var boltGlow = (authored && authored.toLowerCase() !== 'currentcolor')
      ? authored : '#8fc2ff';
    function heatFilter(level) {
      var outer = 6 + level * 22;                 // coloured halo: 6 → 28px
      var warm = 4 + level * 16;                  // warm/amber mid layer
      var core = level * 12;                      // white-hot core (0 at rest)
      return 'drop-shadow(0 0 ' + outer + 'px ' + color + ')'
        + ' drop-shadow(0 0 ' + warm + 'px ' + heatTint + ')'
        + ' drop-shadow(0 0 ' + core + 'px rgba(255,255,255,'
        + (0.35 + level * 0.6).toFixed(3) + '))';
    }
    // Smooth the ramp between strike bumps (restored on cleanup).
    var filter0 = el.style.filter, trans0 = el.style.transition;
    el.style.transition = 'filter 170ms ease';
    el.style.filter = heatFilter(0.15); // subtle base glow while the sky darkens

    // (3) The bolt canvas, on top of both overlays.
    var canvas = document.createElement('canvas');
    var dpr = window.devicePixelRatio || 1;
    canvas.width = Math.round(vw * dpr);
    canvas.height = Math.round(vh * dpr);
    canvas.style.cssText = 'position:fixed;left:0;top:0;width:100vw;height:100vh;'
      + 'pointer-events:none;z-index:2147483647;';
    document.body.appendChild(canvas);
    var sctx = canvas.getContext('2d');
    sctx.scale(dpr, dpr);

    // Teardown: reached by the normal fade-out, OR immediately on scroll — the
    // darkness + bolts are fixed overlays anchored to where the equation WAS,
    // so a scrolled page must get its lights back at once (Charles,
    // 2026-07-06). `dead` parks every pending strike frame/timer.
    var dead = false;
    var cleanup = scrollKillable(function () {
      dead = true;
      backdrop.remove(); flash.remove(); canvas.remove();
      el.style.filter = filter0; el.style.transition = trans0;
    });

    // Reduced motion: no darkening flurry, no strobing, no heat ramp. A brief
    // soft dim + one faint static arc + a soft STEADY glow, then restore.
    if (reduced) {
      backdrop.style.setProperty('--lx-dark', '0.5');
      el.style.filter = heatFilter(0.3); // gentle steady glow, no pulsing
      requestAnimationFrame(function () { backdrop.style.opacity = '1'; });
      sctx.globalAlpha = 0.55; sctx.strokeStyle = boltColor;
      sctx.shadowColor = boltGlow; sctx.shadowBlur = 10; sctx.lineWidth = 2;
      stroke(sctx, jagged(0, vh / 2, cx, cy, 8, 6));
      setTimeout(function () { backdrop.style.opacity = '0'; }, 520);
      setTimeout(cleanup, 950);
      return;
    }

    requestAnimationFrame(function () { backdrop.style.opacity = '1'; });

    // One strike: quickly draw converging jagged bolts to the centre, then at
    // convergence FLASH the backdrop bright (~70ms) — drop its darkness, pulse
    // the white veil, AND heat the equation hotter (level) — before settling
    // back to dark while the equation holds its new, hotter glow.
    function strike(level, done) {
      var DRAW = 130, t0 = performance.now();
      function frame(now) {
        if (dead) { return; } // scrolled away mid-strike: overlays already gone
        var p = Math.min(1, (now - t0) / DRAW);
        sctx.clearRect(0, 0, vw, vh);
        sctx.strokeStyle = boltColor; sctx.shadowColor = boltGlow; sctx.shadowBlur = 16;
        sources.forEach(function (s) {
          for (var k = 0; k < 2; k++) { // 2 forked bolts per source
            var pts = jagged(s[0], s[1], cx, cy, 12, 18 + k * 10);
            sctx.lineWidth = 2 + k; sctx.globalAlpha = 0.9;
            stroke(sctx, pts, Math.floor(pts.length * p));
          }
        });
        if (p < 1) { requestAnimationFrame(frame); return; }
        backdrop.style.setProperty('--lx-dark', '0.55'); // night lights up
        flash.style.opacity = '1';
        el.style.filter = heatFilter(level); // charge pulses the math hotter
        setTimeout(function () {
          if (dead) { return; } // never re-heat the equation after teardown
          backdrop.style.setProperty('--lx-dark', '0.94'); // settle back to dark
          flash.style.opacity = '0';
          sctx.clearRect(0, 0, vw, vh);
          done();
        }, 70);
      }
      requestAnimationFrame(frame);
    }

    // ~3 strikes with a dark gap between; each strikes hotter than the last,
    // peaking (white-hot) at the climax. Then fade the backdrop + canvas out
    // (~400ms), COOL the equation back to normal, and restore cleanly.
    var LEVELS = [0.45, 0.72, 1.0]; // subtle → hot → white-hot climax
    var i = 0;
    function next() {
      if (dead) { return; }
      if (i >= LEVELS.length) {
        backdrop.style.transition = 'opacity 400ms ease';
        canvas.style.transition = 'opacity 400ms ease';
        el.style.transition = 'filter 400ms ease';
        backdrop.style.opacity = '0'; canvas.style.opacity = '0';
        el.style.filter = heatFilter(0); // cool back down as the storm fades
        setTimeout(cleanup, 420);
        return;
      }
      strike(LEVELS[i++], function () { setTimeout(next, 200); });
    }
    setTimeout(next, 170); // let the darken settle before the first strike
  }

  // The equation writes itself: ink each glyph <path> in stroke-by-stroke,
  // staggered left-to-right, then settle to the filled glyph. Special-cased like
  // lightning/storm — but this one toggles PRESENTATION attributes on the existing
  // inner-<svg> paths (stroke / fill / stroke-dashoffset); it adds NO element to the
  // SVG, so the containment contract is unchanged.
  function handscribe(el) {
    var svg = el.querySelector('svg');
    if (!svg) { return; }
    var paths = Array.prototype.slice.call(svg.querySelectorAll('path'));
    if (!paths.length || reduced) { return; } // reduced motion: leave it static
    paths.sort(function (a, b) {
      try { return a.getBBox().x - b.getBBox().x; } catch (e) { return 0; }
    });
    var DRAW = 460, FILL = 260, STAGGER = 55;
    paths.forEach(function (p, i) {
      var len;
      try { len = p.getTotalLength() || 100; } catch (e) { len = 100; }
      p.style.stroke = 'currentColor';
      p.style.strokeWidth = '1.5';
      p.style.fill = 'transparent';
      p.style.strokeDasharray = len;
      p.style.strokeDashoffset = len;
      setTimeout(function () {
        p.style.transition = 'stroke-dashoffset ' + DRAW + 'ms ease, fill '
          + FILL + 'ms ease ' + Math.round(DRAW * 0.6) + 'ms';
        p.style.strokeDashoffset = '0';
        p.style.fill = 'currentColor';
        setTimeout(function () { // settle to a pristine filled glyph
          p.style.stroke = '';
          p.style.strokeWidth = '';
          p.style.strokeDasharray = '';
          p.style.strokeDashoffset = '';
          p.style.transition = '';
        }, DRAW + FILL + 120);
      }, i * STAGGER);
    });
  }

  // HOLOGRAM. Flickers in as a cyan wireframe: scanlines, RGB-split jitter,
  // parallax tilt, flicker dropouts, then a subtle idle loop. Tints/filters/
  // transforms the container el and drives a pointer-events-none scanline body
  // overlay; nothing reaches the inner <svg>.
  function hologram(el) {
    if (el._lxHolo) { return; }
    el._lxHolo = true;
    var authored = (el.style.getPropertyValue('--lx-glow-color') || '').trim();
    var tint = (authored && authored.toLowerCase() !== 'currentcolor')
      ? authored : '#5fe8ff';
    // Capture pristine styles BEFORE any mutation (Lattice: capturing after
    // the tint meant teardown "restored" the hologram look, not the original).
    var col0 = el.style.color, fil0 = el.style.filter,
        tf0 = el.style.transform, trans0 = el.style.transition, op0 = el.style.opacity;
    el.style.color = tint;
    function aberr(px) {
      return 'drop-shadow(' + px + 'px 0 0 rgba(255,44,120,.5))'
        + ' drop-shadow(' + (-px) + 'px 0 0 rgba(60,220,255,.55))'
        + ' drop-shadow(0 0 7px ' + tint + ')';
    }
    el.style.filter = aberr(1.2);
    var scan = document.createElement('div');
    scan.setAttribute('data-lx-fx-overlay', 'hologram');
    scan.style.cssText = 'position:fixed;pointer-events:none;z-index:2147483646;'
      + 'mix-blend-mode:screen;background:repeating-linear-gradient(0deg,'
      + ' rgba(95,232,255,.14) 0px, rgba(95,232,255,.14) 1px,'
      + ' transparent 1px, transparent 3px);';
    document.body.appendChild(scan);
    el._lxHolo = scan;
    function place() {
      var r = el.getBoundingClientRect();
      scan.style.left = r.left + 'px'; scan.style.top = r.top + 'px';
      scan.style.width = r.width + 'px'; scan.style.height = r.height + 'px';
    }
    place();
    window.addEventListener('resize', place);
    // The idle loop runs indefinitely, so teardown is MANDATORY, not optional
    // (the leak the review flagged HIGH: no clearInterval, listeners + overlay
    // outlived the element forever). die() ends it on scroll and restores
    // everything; the interval + resize listener + overlay all release.
    // ALSO reachable off-scroll (plan 62fafe76): scrollKillable only observes real
    // SCROLLING, so in a no-scroll SPA that removes the equation the interval would
    // leak forever — the idle tick below self-checks el.isConnected, and an
    // explicit LatteXFx.destroy(el) can end it deterministically, via __lxHoloDie.
    var idle = 0;
    var die = scrollKillable(function () {
      if (idle) { clearInterval(idle); idle = 0; }
      window.removeEventListener('resize', place);
      scan.remove();
      el._lxHolo = false;
      el.__lxHoloDie = null;
      el.style.color = col0; el.style.filter = fil0; el.style.transform = tf0;
      el.style.transition = trans0; el.style.opacity = op0;
    });
    el.__lxHoloDie = die;
    if (reduced) { return; }
    scan.style.animation = 'lx-holo-scan 1.1s linear infinite';
    el.style.transition = 'transform 1400ms ease-in-out, opacity 90ms linear';
    var seq = ['0', '1', '.2', '1', '.5', '1'], j = 0;
    (function boot() {
      if (die.dead()) { return; }
      if (j < seq.length) { el.style.opacity = seq[j++]; setTimeout(boot, 55); return; }
      var dir = 1;
      idle = setInterval(function () {
        if (die.dead()) { clearInterval(idle); idle = 0; return; }
        if (el.isConnected === false) { die(); return; } // detached: end perpetual work
        place();
        dir = -dir;
        el.style.transform = 'perspective(440px) rotateY(' + (dir * 6) + 'deg)';
        if (Math.random() < 0.5) {
          el.style.filter = aberr(3 + Math.random() * 3);
          setTimeout(function () { if (!die.dead()) { el.style.filter = aberr(1.2); } }, 90);
        }
        if (Math.random() < 0.28) {
          el.style.opacity = '.4';
          setTimeout(function () { if (!die.dead()) { el.style.opacity = '1'; } }, 70);
        }
      }, 900);
    })();
  }

  // NEON SIGN. Buzzes to life: failed stuttering ignitions, then a steady hum —
  // one glyph left flickering forever. Drives a drop-shadow bloom + opacity on the
  // container and toggles opacity on ONE existing inner-<svg> path; no <svg> edits.
  function neonsign(el) {
    // Re-entry guard: neonsign was the ONLY JS effect without one, so every
    // mouseenter (data-lx-fx-hover=neonsign) stacked a fresh perpetual flicker
    // loop (review HIGH). Guard blocks re-entry; the loop stops when the flag
    // clears (the element leaving the DOM is out of scope, but re-trigger no
    // longer compounds, which was the actual observed leak).
    if (el.__lxNeon) { return; }
    el.__lxNeon = true;
    var color = resolveColor(el);
    function glow(on) {
      var core = 2 + on * 1;
      var near = 4 + on * 5;
      var halo = 6 + on * 13;
      return 'drop-shadow(0 0 ' + core + 'px rgba(255,255,255,'
          + (0.3 + on * 0.5).toFixed(3) + '))'
        + ' drop-shadow(0 0 ' + near + 'px rgba(255,255,255,'
          + (on * 0.35).toFixed(3) + '))'
        + ' drop-shadow(0 0 ' + halo + 'px ' + color + ')';
    }
    if (reduced) { el.style.filter = glow(1); return; }
    el.style.transition = 'filter 40ms linear, opacity 40ms linear';
    var seq = [
      [0,   0.35, 1],
      [70,  0,    0.25],
      [150, 0.8,  1],
      [210, 0,    0.25],
      [340, 0.5,  1],
      [430, 1,    1]
    ];
    seq.forEach(function (s) {
      setTimeout(function () {
        el.style.opacity = String(s[2]);
        el.style.filter = glow(s[1]);
      }, s[0]);
    });
    var paths = el.querySelectorAll('svg path');
    if (!paths.length) { el.__lxNeon = false; return; }
    var broken = paths[Math.floor(paths.length / 2)];
    broken.style.transition = 'opacity 60ms linear';
    // The "one glyph flickers forever" idle loop is scroll-killable so it
    // releases its timer + clears the guard on scroll-away (the loop no longer
    // outlives the element unconditionally). ALSO off-scroll (plan 62fafe76):
    // scrollKillable only observes real SCROLLING, so a no-scroll SPA that removes
    // the equation would leak this perpetual timeout chain forever — flicker()
    // self-checks el.isConnected each tick, and LatteXFx.destroy(el) can end it
    // deterministically via __lxNeonDie.
    var timer = 0;
    var die = scrollKillable(function () {
      if (timer) { clearTimeout(timer); timer = 0; }
      broken.style.opacity = ''; broken.style.transition = '';
      el.__lxNeon = false;
      el.__lxNeonDie = null;
    });
    el.__lxNeonDie = die;
    function flicker() {
      if (die.dead()) { return; }
      if (el.isConnected === false) { die(); return; } // detached: stop rescheduling
      var dropout = Math.random() < 0.35;
      broken.style.opacity = dropout
        ? (Math.random() < 0.5 ? '0.15' : '0.6') : '1';
      var next = dropout ? 40 + Math.random() * 90 : 300 + Math.random() * 1500;
      timer = setTimeout(flicker, next);
    }
    timer = setTimeout(flicker, 600);
  }

  // CRYSTALLIZE. A frost front sweeps across (a body-level frost pane, clip-swept),
  // an icy blue-white tint fits over the glyphs (filter on el), then sparkle motes
  // pop. Filter/clip on el + separate body overlays; no element into the inner <svg>.
  function crystallize(el) {
    var svg = el.querySelector('svg');
    if (!svg) { return; }
    var gleam = resolveColor(el);
    var icy = 'brightness(1.14) saturate(0.6) sepia(0.18) hue-rotate(165deg)';
    var trans0 = el.style.transition;
    if (reduced) {
      el.style.filter = icy + ' drop-shadow(0 0 4px ' + gleam + ')';
      return;
    }
    var r = el.getBoundingClientRect();
    var pane = document.createElement('div');
    pane.setAttribute('data-lx-fx-overlay', 'crystallize');
    pane.style.cssText = 'position:fixed;pointer-events:none;z-index:2147483646;'
      + 'left:' + r.left + 'px;top:' + r.top + 'px;width:' + r.width + 'px;'
      + 'height:' + r.height + 'px;opacity:0.85;mix-blend-mode:screen;'
      + 'clip-path:inset(0 100% 0 0);transition:clip-path 900ms ease;'
      // Frost tint derives from gleam (the author's fx.glow-color, else the
      // equation's own ink) — the old hardcoded ice-blue vanished on white
      // backgrounds (Charles, 2026-07-06). color-mix keeps it translucent.
      + 'background:linear-gradient(115deg, color-mix(in srgb, ' + gleam + ' 45%, transparent) 0%,'
      + ' color-mix(in srgb, ' + gleam + ' 18%, transparent) 45%,'
      + ' color-mix(in srgb, ' + gleam + ' 38%, transparent) 100%),'
      + ' repeating-linear-gradient(60deg, color-mix(in srgb, ' + gleam + ' 14%, transparent) 0 6px,'
      + ' rgba(255,255,255,0) 6px 13px);';
    document.body.appendChild(pane);
    el.style.transition = 'filter 900ms ease';
    el.style.filter = icy + ' blur(1.4px) drop-shadow(0 0 5px ' + gleam + ')';
    requestAnimationFrame(function () {
      pane.style.clipPath = 'inset(0 0 0 0)';
      el.style.filter = icy + ' blur(0px) drop-shadow(0 0 6px ' + gleam + ')';
    });
    var motes = [];
    for (var i = 0; i < 7; i++) {
      (function (i) {
        var s = document.createElement('div');
        var sz = 3 + Math.random() * 3;
        var px = r.left + Math.random() * r.width;
        var py = r.top + Math.random() * r.height;
        s.style.cssText = 'position:fixed;pointer-events:none;z-index:2147483647;'
          + 'left:' + px + 'px;top:' + py + 'px;width:' + sz + 'px;height:' + sz
          + 'px;margin:' + (-sz / 2) + 'px 0 0 ' + (-sz / 2) + 'px;border-radius:50%;'
          + 'background:color-mix(in srgb, ' + gleam + ' 35%, #fff);box-shadow:0 0 6px 2px ' + gleam + ';opacity:0;'
          + 'transform:scale(0.2);transition:transform 260ms ease, opacity 260ms ease;';
        document.body.appendChild(s);
        motes.push(s);
        setTimeout(function () {
          s.style.opacity = '1'; s.style.transform = 'scale(1.3)';
          setTimeout(function () {
            s.style.opacity = '0'; s.style.transform = 'scale(0.4)';
          }, 200);
        }, 620 + i * 55);
      })(i);
    }
    setTimeout(function () {
      pane.remove();
      motes.forEach(function (m) { m.remove(); });
      el.style.transition = 'filter 500ms ease';
      el.style.filter = 'brightness(1.05) saturate(0.85) drop-shadow(0 0 3px ' + gleam + ')';
      setTimeout(function () { el.style.transition = trans0; }, 520);
    }, 1300);
  }

  // BLUEPRINT. The container flips to a cyan drafting field (.lx-blueprint), the
  // glyphs stroke-draw in white linework, and a body-level guide overlay (frame,
  // tangent circles, centre lines) fades as they resolve. Toggles presentation on
  // the existing paths + a separate body overlay; no element into the inner <svg>.
  function blueprint(el) {
    el.classList.add('lx-blueprint');
    var svg = el.querySelector('svg');
    var paths = svg
      ? Array.prototype.slice.call(svg.querySelectorAll('path'))
      : [];
    var WHITE = '#eaf3ff';
    if (reduced) {
      paths.forEach(function (p) { p.style.fill = WHITE; });
      return;
    }
    var r = el.getBoundingClientRect();
    var w = r.width, h = r.height, cx = w / 2, cy = h / 2;
    var rad = Math.min(w, h) / 2;
    var guide = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
    guide.setAttribute('width', w); guide.setAttribute('height', h);
    guide.setAttribute('fill', 'none');
    guide.setAttribute('stroke', 'rgba(226,238,255,0.55)');
    guide.setAttribute('stroke-width', '1');
    guide.style.cssText = 'position:fixed;pointer-events:none;overflow:visible;'
      + 'z-index:2147483647;opacity:0;transition:opacity 300ms ease;'
      + 'left:' + r.left + 'px;top:' + r.top + 'px;'
      + 'width:' + w + 'px;height:' + h + 'px;';
    guide.innerHTML =
      '<rect x="1" y="1" width="' + (w - 2) + '" height="' + (h - 2)
        + '" stroke-dasharray="4 4"/>'
      + '<circle cx="' + cx + '" cy="' + cy + '" r="' + rad + '"/>'
      + '<circle cx="' + cx + '" cy="' + cy + '" r="' + (rad * 0.62) + '"/>'
      + '<line x1="0" y1="' + cy + '" x2="' + w + '" y2="' + cy + '"/>'
      + '<line x1="' + cx + '" y1="0" x2="' + cx + '" y2="' + h + '"/>';
    document.body.appendChild(guide);
    requestAnimationFrame(function () { guide.style.opacity = '1'; });
    var DRAW = 620, STAGGER = 60, last = 0;
    paths.sort(function (a, b) {
      try { return a.getBBox().x - b.getBBox().x; } catch (e) { return 0; }
    });
    paths.forEach(function (p, i) {
      var len;
      try { len = p.getTotalLength() || 100; } catch (e) { len = 100; }
      var delay = i * STAGGER;
      if (delay > last) { last = delay; }
      p.style.stroke = WHITE;
      p.style.strokeWidth = '1.2';
      p.style.fill = 'none';
      p.style.strokeDasharray = len;
      p.style.strokeDashoffset = len;
      setTimeout(function () {
        p.style.transition = 'stroke-dashoffset ' + DRAW
          + 'ms ease, fill 300ms ease ' + DRAW + 'ms';
        p.style.strokeDashoffset = '0';
        p.style.fill = WHITE;
      }, delay);
    });
    var total = last + DRAW + 300;
    setTimeout(function () { guide.style.opacity = '0'; }, Math.max(0, total - 260));
    setTimeout(function () { guide.remove(); }, total + 120);
  }

  // WOBBLE. On its trigger the glyphs jiggle like jelly: each does an AUTONOMOUS
  // damped VERTICAL bob decaying over ~1.4s, staggered left-to-right so it ripples
  // across the equation. Pure translateY (origin-independent) — NO rotation (which
  // read as an eraser sweep) and NO transform-box (which left a stray horizontal line);
  // it toggles style.transform on the existing paths only — nothing touches the <svg>.
  function wobble(el) {
    if (reduced) { return; }
    var svg = el.querySelector('svg');
    if (!svg) { return; }
    var paths = Array.prototype.slice.call(svg.querySelectorAll('path'));
    if (!paths.length) { return; }
    paths.sort(function (a, b) {          // left-to-right so the ripple reads
      try { return a.getBBox().x - b.getBBox().x; } catch (e) { return 0; }
    });
    var DUR = 1400; // ms of wobble per glyph
    paths.forEach(function (p, i) {
      setTimeout(function () {                     // stagger → the ripple
        var wobbleAmp = 5 + Math.random() * 2.5;   // peak PX of bounce
        var freq = 5 + Math.random() * 2;          // oscillations over the life
        var t0 = performance.now();
        function step(now) {
          var t = (now - t0) / DUR;                // 0..1
          if (t >= 1) { clearPathDelta(p); return; }   // pristine at rest
          var env = (1 - t) * (1 - t);             // ease-out decay envelope
          var ty = -Math.sin(t * freq * Math.PI * 2) * wobbleAmp * env;
          setPathDelta(p, 'translateY(' + ty.toFixed(2) + 'px)'); // composes with placement
          requestAnimationFrame(step);
        }
        requestAnimationFrame(step);
      }, i * 50);
    });
  }

  // GRAVWELL. Armed on enter: wire a click listener onto each glyph path. On click,
  // the source glyph becomes a gravity well — every other nearby glyph is gently pulled
  // TOWARD it and SHRINKS as it's drawn in (1/r² reach), then eases back out. rAF-driven;
  // only toggles transform on the existing paths (set while animating, cleared at rest).
  // Simple by design: no spiral, no spin, no overlay orb — just pull + shrink.
  // GRAVWELL — the spiral black-hole + eclipse (Charles's redesign, 2026-07-06):
  // armed on enter; CLICK ANYWHERE on the equation and that point becomes the
  // well — an eclipse-like orb (dark disc + bright corona, a body overlay)
  // opens there while EVERY glyph shrinks and SPIRALS into it, holds a dark
  // beat, then spirals back out as the orb collapses. Click point maps to
  // user space via the svg screen CTM; per-glyph motion is a user-space delta
  // composed with placement (setPathDelta), spinning about the well while the
  // radius and scale collapse. Scroll ends the show instantly (the orb is a
  // fixed overlay). Nothing is ever added to the inner <svg>.
  function gravwell(el) {
    var svg = el.querySelector('svg');
    if (!svg || reduced) { return; }
    if (el.__lxWellArmed) { return; }
    el.__lxWellArmed = true;
    var paths = Array.prototype.slice.call(svg.querySelectorAll('path'));
    if (!paths.length) { return; }
    var IN = 900, HOLD = 240, BACK = 520;
    var TURNS = 1.35;   // how far each glyph swings around the well on the way in
    var busy = false;
    svg.style.cursor = 'pointer';
    svg.addEventListener('click', function (ev) {
      if (busy) { return; }
      busy = true;

      // The CLICK POINT is the well: map client → svg user space.
      var well;
      try {
        var ctm = svg.getScreenCTM().inverse();
        well = { x: ctm.a * ev.clientX + ctm.c * ev.clientY + ctm.e,
                 y: ctm.b * ev.clientX + ctm.d * ev.clientY + ctm.f };
      } catch (e) { busy = false; return; }
      // user → screen scale, for sizing the overlay orb in page px.
      var toScreen = svg.getScreenCTM();
      var wellPx = { x: toScreen.a * well.x + toScreen.c * well.y + toScreen.e,
                     y: toScreen.b * well.x + toScreen.d * well.y + toScreen.f };

      var movers = [];
      paths.forEach(function (p) {
        var c = userCentre(p);
        if (!c) { return; }
        var dx = c.x - well.x, dy = c.y - well.y;
        movers.push({ p: p, r0: Math.sqrt(dx * dx + dy * dy),
                      a0: Math.atan2(dy, dx), cx: c.x, cy: c.y });
      });
      if (!movers.length) { busy = false; return; }

      // The eclipse orb: a dark disc with a bright corona at the click point.
      var orb = document.createElement('div');
      var R = 26;
      orb.style.cssText = 'position:fixed;pointer-events:none;z-index:2147483647;'
        + 'left:' + (wellPx.x - R) + 'px;top:' + (wellPx.y - R) + 'px;'
        + 'width:' + (R * 2) + 'px;height:' + (R * 2) + 'px;border-radius:50%;'
        + 'background:radial-gradient(circle, #05060a 0 58%, rgba(5,6,10,0.9) 62%, transparent 70%);'
        + 'box-shadow:0 0 18px 4px rgba(255,244,214,0.85), 0 0 46px 10px rgba(255,214,140,0.35);'
        + 'transform:scale(0.1);opacity:0;transition:transform 260ms ease, opacity 200ms ease;';
      document.body.appendChild(orb);
      requestAnimationFrame(function () { orb.style.transform = 'scale(1)'; orb.style.opacity = '1'; });

      var raf = 0, t0 = performance.now();
      var die = scrollKillable(function () {
        if (raf) { cancelAnimationFrame(raf); raf = 0; }
        orb.remove();
        movers.forEach(function (m) { clearPathDelta(m.p); m.p.style.opacity = ''; });
        busy = false;
      });

      function frame(now) {
        if (die.dead()) { return; }
        var e = now - t0, k, phase;
        if (e < IN) { var u = e / IN; k = u * u * (3 - 2 * u); phase = 0; }
        else if (e < IN + HOLD) { k = 1; phase = 1; }
        else {
          var d = (e - IN - HOLD) / BACK;
          if (d >= 1) { die(); return; }
          k = 1 - d * d * (3 - 2 * d); phase = 2;
        }
        movers.forEach(function (m) {
          var r = m.r0 * (1 - 0.96 * k);              // radius collapses into the well
          var a = m.a0 + TURNS * 2 * Math.PI * k;     // ...while swinging around it
          var nx = well.x + r * Math.cos(a);
          var ny = well.y + r * Math.sin(a);
          var sc = Math.max(0.06, 1 - 0.94 * k);      // shrink toward a point
          m.p.style.opacity = String(Math.max(0.15, 1 - 0.75 * k));
          setPathDelta(m.p, 'translate(' + (nx - m.cx).toFixed(2) + 'px,'
            + (ny - m.cy).toFixed(2) + 'px) ' + pivotScaleDelta(m.p, sc.toFixed(3)));
        });
        if (phase === 1) {                            // dark beat: the orb pulses
          orb.style.transform = 'scale(' + (1 + 0.08 * Math.sin(e / 40)).toFixed(3) + ')';
        }
        if (phase === 2 && e > IN + HOLD + BACK * 0.5) {
          orb.style.opacity = '0'; orb.style.transform = 'scale(0.15)'; // orb collapses
        }
        raf = requestAnimationFrame(frame);
      }
      raf = requestAnimationFrame(frame);
    });
  }

  // MATRIXRAIN. "Digital rain" falls DOWN — but BEHIND the equation, which stays fully
  // visible in front. A child <canvas> at z-index:-1 covers the element's box (with
  // headroom above so streams fall INTO it); the rain is tinted to the equation's OWN
  // ink colour (matches the theme) — an author's fx.glow-color overrides. A
  // destination-out fade tapers each column to TRANSPARENT (no dark box behind the math).
  function matrixrain(el) {
    if (reduced) { return; }
    // Re-entry guard: a re-trigger while active would re-read the ALREADY
    // position:relative/isolation:isolate inline styles as its "originals" and
    // corrupt the restore (review LOW). The cleanup clears the flag.
    if (el.__lxRain) { return; }
    var svg = el.querySelector('svg');
    if (!svg) { return; }
    el.__lxRain = true;
    var authored = (el.style.getPropertyValue('--lx-glow-color') || '').trim();
    var ink = (authored && authored.toLowerCase() !== 'currentcolor')
      ? authored : resolveColor(el);
    var r = el.getBoundingClientRect();
    if (!r.width || !r.height) { el.__lxRain = false; return; }
    var pos0 = el.style.position, iso0 = el.style.isolation;
    if (getComputedStyle(el).position === 'static') { el.style.position = 'relative'; }
    el.style.isolation = 'isolate';            // own stacking context so the z-index:-1
                                               // canvas stays BEHIND the glyphs but IN
                                               // FRONT of the page (else it escapes, unseen)
    var padTop = Math.round(r.height * 1.4);   // headroom so streams fall INTO the box
    var W = r.width, H = r.height + padTop;
    var canvas = document.createElement('canvas');
    var dpr = window.devicePixelRatio || 1;
    canvas.width = Math.round(W * dpr);
    canvas.height = Math.round(H * dpr);
    canvas.style.cssText = 'position:absolute;pointer-events:none;z-index:-1;'
      + 'left:0;top:' + (-padTop) + 'px;width:' + W + 'px;height:' + H + 'px;'
      + 'opacity:0;transition:opacity 400ms ease;';
    el.appendChild(canvas);                    // BEHIND the glyphs (z-index:-1)
    var ctx = canvas.getContext('2d');
    ctx.scale(dpr, dpr);
    requestAnimationFrame(function () { canvas.style.opacity = '1'; });
    var GLYPHS = ('アイウエオカキクケコサシスセソタチツテトナニヌネノ'
      + '0123456789=+-<>{}πΔΣ√').split('');
    function rnd() { return GLYPHS[(Math.random() * GLYPHS.length) | 0]; }
    var FONT = 14;
    ctx.font = FONT + 'px ui-monospace, "SF Mono", Menlo, monospace';
    ctx.textBaseline = 'top';
    var cols = Math.max(1, Math.floor(W / (FONT * 0.72)));
    var colW = W / cols;
    var rows = Math.max(1, Math.ceil(H / FONT) + 1);
    var drops = [];
    for (var c = 0; c < cols; c++) {
      drops.push({ y: -Math.floor(Math.random() * rows),
        v: 0.30 + Math.random() * 0.45, lastRow: -999 });
    }
    var raf = 0, timers = [], done = false;
    var stop = scrollKillable(function () { // scroll ends the rain at once
      done = true;
      if (raf) { cancelAnimationFrame(raf); raf = 0; }
      timers.forEach(clearTimeout); timers.length = 0;
      canvas.remove();
      el.style.position = pos0;
      el.style.isolation = iso0;
      el.__lxRain = false;
    });
    var t0 = performance.now(), RAIN = 2000;
    function frame(now) {
      if (done) { return; }
      ctx.globalCompositeOperation = 'destination-out';   // fade the tail to TRANSPARENT
      ctx.globalAlpha = 1;
      ctx.fillStyle = 'rgba(0,0,0,0.10)';
      ctx.fillRect(0, 0, W, H);
      ctx.globalCompositeOperation = 'source-over';
      ctx.globalAlpha = 0.92;
      ctx.fillStyle = ink;
      for (var c = 0; c < cols; c++) {
        var d = drops[c], row = Math.floor(d.y);
        if (row !== d.lastRow && row >= 0) {              // new glyph only on row advance
          ctx.fillText(rnd(), c * colW, row * FONT);
          d.lastRow = row;
        }
        d.y += d.v;
        if (row * FONT > H && Math.random() > 0.975) {
          d.y = -1; d.lastRow = -999; d.v = 0.30 + Math.random() * 0.45;
        }
      }
      if (now - t0 < RAIN) { raf = requestAnimationFrame(frame); return; }
      raf = 0;
      canvas.style.opacity = '0';
      timers.push(setTimeout(stop, 440));
    }
    raf = requestAnimationFrame(frame);
  }

  // SUPERNOVA. Click to detonate: the equation COLLAPSES to a hot point, then
  // DETONATES on a body <canvas> — radial flash + expanding shockwave ring + stardust
  // flung with gravity/drag + fading trails — then RE-CONDENSES from the dust.
  function supernova(el) {
    if (el.__lxNova) { return; }
    el.__lxNova = true;
    var r = el.getBoundingClientRect();
    var cx = r.left + r.width / 2, cy = r.top + r.height / 2;
    var color = resolveColor(el);
    var palette = ['#ffffff', '#ffe9b0', '#ffab4d', color, '#7fd4ff'];
    var trans0 = el.style.transition, tf0 = el.style.transform, fil0 = el.style.filter;
    function restore() {
      el.__lxNova = false;
      el.style.transition = trans0; el.style.transform = tf0; el.style.filter = fil0;
    }
    if (reduced) {
      var veil = document.createElement('div');
      veil.style.cssText = 'position:fixed;inset:0;pointer-events:none;'
        + 'z-index:2147483647;opacity:0;transition:opacity 120ms ease;background:'
        + 'radial-gradient(circle 120px at ' + cx + 'px ' + cy + 'px,'
        + ' rgba(255,255,255,0.7) 0%, rgba(255,255,255,0) 70%);';
      document.body.appendChild(veil);
      requestAnimationFrame(function () { veil.style.opacity = '1'; });
      setTimeout(function () { veil.style.opacity = '0'; }, 130);
      setTimeout(function () { veil.remove(); el.__lxNova = false; }, 340);
      return;
    }
    el.style.transition = 'transform 170ms cubic-bezier(.6,0,.9,.2), filter 170ms ease';
    el.style.transform = 'scale(0.1)';
    el.style.filter = 'brightness(2.6) drop-shadow(0 0 10px ' + color + ')';
    var vw = window.innerWidth, vh = window.innerHeight;
    var dpr = window.devicePixelRatio || 1;
    var canvas = document.createElement('canvas');
    canvas.width = Math.round(vw * dpr);
    canvas.height = Math.round(vh * dpr);
    canvas.style.cssText = 'position:fixed;left:0;top:0;width:100vw;height:100vh;'
      + 'pointer-events:none;z-index:2147483647;';
    document.body.appendChild(canvas);
    var ctx = canvas.getContext('2d');
    ctx.scale(dpr, dpr);
    var N = 64, parts = [];
    for (var i = 0; i < N; i++) {
      var ang = (i / N) * Math.PI * 2 + Math.random() * 0.35;
      var speed = 120 + Math.random() * 340;
      parts.push({ x: cx, y: cy, px: cx, py: cy,
        vx: Math.cos(ang) * speed, vy: Math.sin(ang) * speed,
        life: 1, decay: 0.6 + Math.random() * 0.7,
        col: palette[(Math.random() * palette.length) | 0],
        size: 1 + Math.random() * 2 });
    }
    var GRAV = 220, DRAG = 0.86;
    var LIFE = 900, DETONATE = 170;
    var raf = 0, timers = [], t0 = 0, lastT = 0;
    var cleanup = scrollKillable(function () { // + ends on scroll
      if (raf) { cancelAnimationFrame(raf); raf = 0; }
      timers.forEach(clearTimeout); timers.length = 0;
      canvas.remove(); restore();
    });
    function frame(now) {
      var e = now - t0;
      var dt = lastT ? Math.min(0.05, (now - lastT) / 1000) : 0.016;
      lastT = now;
      ctx.clearRect(0, 0, vw, vh);
      var fa = Math.max(0, 1 - e / 180);
      if (fa > 0) {
        var fr = 60 + e * 0.5;
        var fg = ctx.createRadialGradient(cx, cy, 0, cx, cy, fr);
        fg.addColorStop(0, 'rgba(255,255,255,' + (0.95 * fa).toFixed(3) + ')');
        fg.addColorStop(0.4, 'rgba(255,220,150,' + (0.6 * fa).toFixed(3) + ')');
        fg.addColorStop(1, 'rgba(255,160,60,0)');
        ctx.fillStyle = fg;
        ctx.beginPath(); ctx.arc(cx, cy, fr, 0, Math.PI * 2); ctx.fill();
      }
      var sa = Math.max(0, 1 - e / 520);
      if (sa > 0) {
        ctx.strokeStyle = 'rgba(255,210,150,' + (0.8 * sa).toFixed(3) + ')';
        ctx.lineWidth = 1 + 5 * sa;
        ctx.beginPath(); ctx.arc(cx, cy, e * 0.9, 0, Math.PI * 2); ctx.stroke();
      }
      for (var i = 0; i < parts.length; i++) {
        var p = parts[i];
        if (p.life <= 0) { continue; }
        p.px = p.x; p.py = p.y;
        p.vy += GRAV * dt;
        var d = Math.pow(DRAG, dt * 60);
        p.vx *= d; p.vy *= d;
        p.x += p.vx * dt; p.y += p.vy * dt;
        p.life -= p.decay * dt;
        ctx.strokeStyle = p.col;
        ctx.globalAlpha = Math.max(0, p.life);
        ctx.lineWidth = p.size;
        ctx.beginPath(); ctx.moveTo(p.px, p.py); ctx.lineTo(p.x, p.y); ctx.stroke();
      }
      ctx.globalAlpha = 1;
      if (e < LIFE) { raf = requestAnimationFrame(frame); } else { cleanup(); }
    }
    timers.push(setTimeout(function () {
      el.style.transition = 'transform 720ms cubic-bezier(.16,1.4,.3,1), filter 640ms ease';
      el.style.transform = tf0 || 'scale(1)';
      el.style.filter = fil0;
      t0 = performance.now();
      raf = requestAnimationFrame(frame);
    }, DETONATE));
  }

  // INKDROP. The equation grows out of a falling ink splat: a dark drop falls, hits
  // the centre, splats into an irregular blot flinging spatter, and the glyphs bloom
  // up out of the settled ink. Body-level drop/splat/spatter overlays, removed on cleanup.
  function inkdrop(el) {
    if (el.__lxInk) { return; }
    // Reduced motion: show plainly and DON'T trip the re-entry guard
    // (Lattice: setting it first made reduced-motion inkdrop one-shot forever).
    if (reduced) { el.style.opacity = '1'; return; }
    el.__lxInk = true;
    var ink = resolveColor(el);
    var trans0 = el.style.transition, to0 = el.style.transformOrigin,
        pos0 = el.style.position, z0 = el.style.zIndex, tf0 = el.style.transform;
    var r = el.getBoundingClientRect();
    var cx = r.left + r.width / 2, cy = r.top + r.height / 2;
    var timers = [];
    function later(fn, ms) { timers.push(setTimeout(fn, ms)); }
    el.style.opacity = '0';
    el.style.position = 'relative';
    el.style.zIndex = '2147483647';
    el.style.transformOrigin = (r.width / 2) + 'px ' + r.height + 'px';
    el.style.transform = 'scale(.32)';
    var FALL = 84, DROP = 260, SPLAT = 210, BLOOM = 440;
    var dSize = 15;
    var drop = document.createElement('div');
    drop.setAttribute('data-lx-fx-overlay', 'inkdrop');
    drop.style.cssText = 'position:fixed;pointer-events:none;z-index:2147483646;background:' + ink
      + ';left:' + (cx - dSize / 2) + 'px;top:' + (cy - dSize / 2 - FALL) + 'px;'
      + 'width:' + dSize + 'px;height:' + dSize + 'px;opacity:.94;'
      + 'border-radius:62% 62% 60% 60% / 82% 82% 54% 54%;transform:scale(.7,1.15);'
      + 'transition:transform ' + DROP + 'ms cubic-bezier(.55,.06,.9,.35);';
    document.body.appendChild(drop);
    var bW = Math.max(30, Math.min(r.width * 0.9, 130)), bH = bW * 0.66;
    var splat = document.createElement('div');
    splat.style.cssText = 'position:fixed;pointer-events:none;z-index:2147483645;background:' + ink
      + ';left:' + cx + 'px;top:' + cy + 'px;width:' + bW + 'px;height:' + bH + 'px;'
      + 'margin:' + (-bH / 2) + 'px 0 0 ' + (-bW / 2) + 'px;opacity:0;'
      + 'border-radius:46% 54% 62% 38% / 58% 44% 56% 42%;transform:scale(0);'
      + 'transition:transform ' + SPLAT + 'ms cubic-bezier(.2,1.35,.4,1), opacity 180ms ease;';
    document.body.appendChild(splat);
    var spatter = [];
    requestAnimationFrame(function () {
      drop.style.transform = 'translateY(' + FALL + 'px) scale(1,.82)';
    });
    later(function () {
      drop.style.opacity = '0';
      splat.style.opacity = '1';
      splat.style.transform = 'scale(1)';
      for (var i = 0; i < 5; i++) {
        (function (i) {
          var ang = Math.PI * 2 * (i / 5) + Math.random() * 0.8;
          var dist = 16 + Math.random() * 24, sz = 3 + Math.random() * 4;
          var d = document.createElement('div');
          d.style.cssText = 'position:fixed;pointer-events:none;z-index:2147483645;background:' + ink
            + ';left:' + cx + 'px;top:' + cy + 'px;width:' + sz + 'px;height:' + sz + 'px;'
            + 'margin:' + (-sz / 2) + 'px 0 0 ' + (-sz / 2) + 'px;border-radius:50%;opacity:.85;'
            + 'transition:transform 300ms cubic-bezier(.15,.7,.3,1), opacity 520ms ease;';
          document.body.appendChild(d);
          spatter.push(d);
          requestAnimationFrame(function () {
            d.style.transform = 'translate(' + (Math.cos(ang) * dist).toFixed(1) + 'px,'
              + (Math.sin(ang) * dist).toFixed(1) + 'px) scale('
              + (0.4 + Math.random() * 0.6).toFixed(2) + ')';
          });
        })(i);
      }
    }, DROP);
    later(function () {
      el.style.transition = 'opacity ' + BLOOM + 'ms ease, transform '
        + BLOOM + 'ms cubic-bezier(.2,1.12,.35,1)';
      el.style.opacity = '1';
      el.style.transform = 'scale(1)';
      splat.style.transition = 'opacity ' + BLOOM + 'ms ease, transform ' + BLOOM + 'ms ease';
      splat.style.transform = 'scale(1.18)';
      splat.style.opacity = '0';
      spatter.forEach(function (d) { d.style.opacity = '0'; });
    }, DROP + SPLAT);
    later(function () {
      timers.length = 0;
      drop.remove(); splat.remove();
      spatter.forEach(function (d) { d.remove(); });
      el.style.transition = trans0; el.style.transform = tf0;
      el.style.transformOrigin = to0; el.style.position = pos0; el.style.zIndex = z0;
    }, DROP + SPLAT + BLOOM + 140);
  }

  // A module-scoped counter so each element's diffusion filter gets a unique id
  // (multiple specimens on the page must not share one <filter>).
  var __lxDiffuseSeq = 0;

  // DIFFUSION. On hover the equation dissolves into ink-in-water — a turbulent
  // spreading blur — and reassembles on leave (reversible). A runtime body-level
  // hidden <svg> holds an feTurbulence→feDisplacementMap filter; el.style.filter
  // points at it and a single rAF loop drives the map's scale + opacity; removed idle.
  function diffusion(el) {
    if (reduced) { return; }
    var st = el.__lxDiffuse;
    if (!st) {
      st = el.__lxDiffuse =
        { p: 0, dir: 1, raf: 0, last: 0, holder: null, map: null };
      el.addEventListener('mouseleave', function () {
        st.dir = -1;
        if (!st.raf) { st.last = 0; st.raf = requestAnimationFrame(step); }
      });
    }
    st.dir = 1;
    ensureFilter();
    if (!st.raf) { st.last = 0; st.raf = requestAnimationFrame(step); }
    function ensureFilter() {
      if (st.holder) { return; }
      var seq = ++__lxDiffuseSeq;
      var id = 'lx-diffuse-' + seq;
      var holder =
        document.createElementNS('http://www.w3.org/2000/svg', 'svg');
      holder.setAttribute('aria-hidden', 'true');
      holder.setAttribute('width', '0');
      holder.setAttribute('height', '0');
      holder.style.cssText =
        'position:absolute;width:0;height:0;overflow:hidden;';
      holder.innerHTML =
        '<defs><filter id="' + id
          + '" x="-50%" y="-50%" width="200%" height="200%">'
        + '<feTurbulence type="fractalNoise" baseFrequency="0.02"'
          + ' numOctaves="2" seed="' + seq + '" result="lx-noise"/>'
        + '<feDisplacementMap in="SourceGraphic" in2="lx-noise" scale="0"'
          + ' xChannelSelector="R" yChannelSelector="G"/>'
        + '</filter></defs>';
      document.body.appendChild(holder);
      st.holder = holder;
      st.map = holder.querySelector('feDisplacementMap');
      el.style.filter = 'url(#' + id + ')';
    }
    function step(now) {
      if (!st.last) { st.last = now; }
      var dt = Math.min(64, now - st.last);
      st.last = now;
      st.p += st.dir * dt / 520;
      if (st.p > 1) { st.p = 1; }
      if (st.p < 0) { st.p = 0; }
      if (st.map) { st.map.setAttribute('scale', (st.p * 30).toFixed(2)); }
      el.style.opacity = (1 - st.p * 0.82).toFixed(3);
      var settled = (st.dir > 0 && st.p >= 1) || (st.dir < 0 && st.p <= 0);
      if (!settled) { st.raf = requestAnimationFrame(step); return; }
      st.raf = 0;
      if (st.dir < 0 && st.p <= 0) {
        el.style.filter = '';
        el.style.opacity = '';
        if (st.holder) { st.holder.remove(); st.holder = null; st.map = null; }
      }
    }
  }

  // REFRACTION. Armed on hover: a small glassy lens (a body-level, pointer-events-none
  // <div> whose backdrop-filter blurs + lifts the glyphs beneath it) follows the
  // pointer across the equation, torn down on pointerleave. Touches nothing in the <svg>.
  function refraction(el) {
    if (reduced) { return; }
    if (el.__lxLensArmed) { return; }
    el.__lxLensArmed = true;
    var lens = null;
    function makeLens(r) {
      var d = Math.max(34, Math.min(120, r.height * 1.6));
      var n = document.createElement('div');
      n.setAttribute('aria-hidden', 'true');
      var s = n.style;
      s.position = 'fixed';
      s.left = '0'; s.top = '0';
      s.width = d + 'px'; s.height = d + 'px';
      s.marginLeft = (-d / 2) + 'px';
      s.marginTop = (-d / 2) + 'px';
      s.borderRadius = '50%';
      s.pointerEvents = 'none';
      s.zIndex = '2147483646';
      s.willChange = 'transform';
      s.backdropFilter = 'blur(1.3px) brightness(1.14) contrast(1.06) saturate(1.12)';
      s.webkitBackdropFilter = s.backdropFilter;
      s.background = 'radial-gradient(circle at 34% 30%, rgba(255,255,255,.38),'
        + ' rgba(255,255,255,.06) 42%, rgba(255,255,255,0) 62%)';
      s.boxShadow = 'inset 0 0 0 1px rgba(255,255,255,.35),'
        + ' inset 0 -6px 12px rgba(0,0,0,.10), 0 3px 10px rgba(0,0,0,.18)';
      s.transition = 'opacity 140ms ease';
      s.opacity = '0';
      document.body.appendChild(n);
      requestAnimationFrame(function () { if (n.parentNode) { n.style.opacity = '1'; } });
      return n;
    }
    function move(ev) {
      if (!lens) { lens = makeLens(el.getBoundingClientRect()); }
      lens.style.transform = 'translate(' + ev.clientX + 'px,' + ev.clientY + 'px)';
    }
    function leave() {
      el.removeEventListener('pointermove', move);
      el.removeEventListener('pointerleave', leave);
      el.__lxLensArmed = false;
      var dead = lens; lens = null;
      if (dead) {
        dead.style.opacity = '0';
        setTimeout(function () {
          if (dead.parentNode) { dead.parentNode.removeChild(dead); }
        }, 160);
      }
    }
    el.addEventListener('pointermove', move);
    el.addEventListener('pointerleave', leave);
  }

  // TELEPORT. A transporter beam-out/in. On click the equation DEMATERIALIZES — el
  // fades out while a body <canvas> over its box draws a rising column of shimmering
  // cyan/white particles behind a soft vertical beam — holds a beat, then REMATERIALIZES
  // as the particles descend/converge and el fades in. opacity/filter on el + a canvas.
  function teleport(el) {
    if (el.__lxTeleporting) { return; }
    el.__lxTeleporting = true;
    var color = resolveColor(el);
    var filter0 = el.style.filter, trans0 = el.style.transition, op0 = el.style.opacity;
    var timers = [];
    function restore() {
      el.style.transition = trans0; el.style.filter = filter0;
      el.style.opacity = op0 || '1'; el.__lxTeleporting = false;
    }
    if (reduced) {
      el.style.transition = 'opacity 240ms ease';
      el.style.opacity = '0';
      timers.push(setTimeout(function () {
        el.style.opacity = '1';
        timers.push(setTimeout(restore, 260));
      }, 420));
      return;
    }
    var r = el.getBoundingClientRect();
    var dpr = window.devicePixelRatio || 1;
    var pad = 30;
    var W = r.width, H = r.height + pad * 2;
    var canvas = document.createElement('canvas');
    canvas.width = Math.round(W * dpr);
    canvas.height = Math.round(H * dpr);
    canvas.style.cssText = 'position:fixed;pointer-events:none;z-index:2147483647;'
      + 'left:' + r.left + 'px;top:' + (r.top - pad) + 'px;'
      + 'width:' + W + 'px;height:' + H + 'px;';
    document.body.appendChild(canvas);
    var ctx = canvas.getContext('2d');
    ctx.scale(dpr, dpr);
    var N = Math.max(90, Math.min(320, Math.round(W * 1.4)));
    var particles = [];
    for (var i = 0; i < N; i++) {
      particles.push({
        x: Math.random() * W,
        y: pad + Math.random() * r.height,
        vx: (Math.random() * 2 - 1) * 0.35,
        vy: -(0.4 + Math.random() * 1.6),
        size: 0.6 + Math.random() * 1.9,
        life: Math.random(),
        c: Math.random() < 0.5 ? '255,255,255' : '150,238,255'
      });
    }
    function reseed() {
      for (var i = 0; i < particles.length; i++) {
        var p = particles[i];
        p.x = Math.random() * W;
        p.y = Math.random() * pad;
        p.vx = (W / 2 - p.x) / (r.height + pad) * (0.6 + Math.random() * 0.8);
        p.vy = 0.5 + Math.random() * 1.7;
        p.life = Math.random();
      }
    }
    var OUT = 620, HOLD = 240, IN = 620, END = OUT + HOLD + IN;
    el.style.transition = 'opacity ' + OUT + 'ms ease, filter ' + OUT + 'ms ease';
    el.style.filter = 'drop-shadow(0 0 6px ' + color + ')';
    el.style.opacity = '0';
    var raf = 0, inStarted = false, t0 = performance.now();
    var cleanup = scrollKillable(function () { // + ends on scroll
      if (raf) { cancelAnimationFrame(raf); raf = 0; }
      timers.forEach(clearTimeout); timers.length = 0;
      canvas.remove();
      restore();
    });
    function draw(now) {
      var t = now - t0;
      if (t >= OUT + HOLD && !inStarted) {
        inStarted = true;
        el.style.transition = 'opacity ' + IN + 'ms ease, filter ' + IN + 'ms ease';
        el.style.filter = filter0 || 'none';
        el.style.opacity = '1';
        reseed();
      }
      var phase = t < OUT ? 0 : (t < OUT + HOLD ? 1 : 2);
      var g = phase === 0 ? Math.min(1, t / OUT)
            : phase === 1 ? 1
            : Math.max(0, 1 - (t - OUT - HOLD) / IN);
      ctx.clearRect(0, 0, W, H);
      ctx.globalCompositeOperation = 'lighter';
      var beam = ctx.createLinearGradient(0, 0, 0, H);
      beam.addColorStop(0, 'rgba(150,238,255,0)');
      beam.addColorStop(0.5, 'rgba(150,238,255,' + (0.22 * g).toFixed(3) + ')');
      beam.addColorStop(1, 'rgba(150,238,255,0)');
      ctx.fillStyle = beam;
      ctx.fillRect(W / 2 - 7, 0, 14, H);
      for (var i = 0; i < particles.length; i++) {
        var p = particles[i];
        p.x += p.vx; p.y += p.vy; p.life += 0.03;
        var tw = 0.5 + 0.5 * Math.sin(p.life * 6.283 + i);
        var a = Math.max(0, g * tw * 0.9);
        if (a <= 0.01) { continue; }
        ctx.beginPath();
        ctx.fillStyle = 'rgba(' + p.c + ',' + a.toFixed(3) + ')';
        ctx.arc(p.x, p.y, p.size, 0, Math.PI * 2);
        ctx.fill();
      }
      if (t >= END) { cleanup(); return; }
      raf = requestAnimationFrame(draw);
    }
    raf = requestAnimationFrame(draw);
  }

  // SHATTER: the equation cracks like glass — a bright crack-web flashes, the
  // pane breaks into triangular shards that scatter outward and hang in zero-g,
  // then the SECOND click magnetically reassembles them (a toggle). The ink is
  // replayed onto a full-viewport body canvas (existing <path>/<rect> data via
  // Path2D — read-only); the element itself just fades under the pane, and the
  // inner <svg> is never touched.
  function shatter(el) {
    if (el.__lxShatter) { el.__lxShatter(); return; } // second click → reassemble
    var svg = el.querySelector('svg');
    if (!svg) { return; }
    var op0 = el.style.opacity, trans0 = el.style.transition;
    if (reduced) {
      // No motion: a quiet fade-out/fade-in toggle.
      el.style.transition = 'opacity 200ms ease';
      el.style.opacity = '0.25';
      el.__lxShatter = function () {
        el.style.opacity = op0 || '1';
        el.style.transition = trans0;
        el.__lxShatter = null;
      };
      return;
    }
    var r = el.getBoundingClientRect();
    var vb = (svg.getAttribute('viewBox') || '').split(/[\s,]+/).map(Number);
    if (vb.length !== 4 || !(vb[2] > 0) || !(vb[3] > 0)) { return; }
    var dpr = window.devicePixelRatio || 1;
    var sx = r.width / vb[2], sy = r.height / vb[3];

    // Replay the ink (paths + rects, their own fills) onto an offscreen pane.
    var pane = document.createElement('canvas');
    pane.width = Math.max(1, Math.round(r.width * dpr));
    pane.height = Math.max(1, Math.round(r.height * dpr));
    var pctx = pane.getContext('2d');
    pctx.scale(dpr * sx, dpr * sy);
    pctx.translate(-vb[0], -vb[1]);
    var ink = getComputedStyle(svg).color || '#000';
    function fillOf(node) {
      var f = node.getAttribute('fill');
      return (!f || f === 'currentColor') ? ink : f;
    }
    Array.prototype.forEach.call(svg.querySelectorAll('path'), function (p) {
      var d = p.getAttribute('d');
      if (!d) { return; }
      pctx.fillStyle = fillOf(p);
      pctx.fill(new Path2D(d));
    });
    Array.prototype.forEach.call(svg.querySelectorAll('rect'), function (q) {
      pctx.fillStyle = fillOf(q);
      pctx.fillRect(+q.getAttribute('x') || 0, +q.getAttribute('y') || 0,
        +q.getAttribute('width') || 0, +q.getAttribute('height') || 0);
    });

    // Jittered triangular shard mesh over the pane (overlay-local coords).
    var CELL = 26, cols = Math.max(2, Math.ceil(r.width / CELL)),
        rows = Math.max(2, Math.ceil(r.height / CELL));
    var gx = [], gy = [];
    for (var i = 0; i <= cols; i++) {
      var jx = (i === 0 || i === cols) ? 0 : (Math.random() * 0.7 - 0.35) * (r.width / cols);
      gx.push((i / cols) * r.width + jx);
    }
    for (var j = 0; j <= rows; j++) {
      var jy = (j === 0 || j === rows) ? 0 : (Math.random() * 0.7 - 0.35) * (r.height / rows);
      gy.push((j / rows) * r.height + jy);
    }
    var shards = [], ccx = r.width / 2, ccy = r.height / 2;
    for (var cj = 0; cj < rows; cj++) {
      for (var ci = 0; ci < cols; ci++) {
        var x0 = gx[ci], x1 = gx[ci + 1], y0 = gy[cj], y1 = gy[cj + 1];
        var tris = Math.random() < 0.5
          ? [[[x0, y0], [x1, y0], [x1, y1]], [[x0, y0], [x1, y1], [x0, y1]]]
          : [[[x0, y0], [x1, y0], [x0, y1]], [[x1, y0], [x1, y1], [x0, y1]]];
        for (var t = 0; t < 2; t++) {
          var tri = tris[t];
          var mx = (tri[0][0] + tri[1][0] + tri[2][0]) / 3;
          var my = (tri[0][1] + tri[1][1] + tri[2][1]) / 3;
          var ang = Math.atan2(my - ccy, mx - ccx) + (Math.random() * 0.6 - 0.3);
          var speed = 90 + Math.random() * 260;
          shards.push({ tri: tri, mx: mx, my: my,
            dx: 0, dy: 0, rot: 0,
            vx: Math.cos(ang) * speed, vy: Math.sin(ang) * speed - 30,
            vr: (Math.random() * 2 - 1) * 2.4,
            bobA: 1 + Math.random() * 1.6, bobW: 0.8 + Math.random() * 1.2,
            bobP: Math.random() * 6.283 });
        }
      }
    }

    // Full-viewport overlay the shards fly across.
    var vw = window.innerWidth, vh = window.innerHeight;
    var canvas = document.createElement('canvas');
    canvas.width = Math.round(vw * dpr);
    canvas.height = Math.round(vh * dpr);
    canvas.style.cssText = 'position:fixed;left:0;top:0;width:100vw;height:100vh;'
      + 'pointer-events:none;z-index:2147483647;';
    document.body.appendChild(canvas);
    var ctx = canvas.getContext('2d');
    ctx.scale(dpr, dpr);

    var CRACK = 150, BREAK = 850;      // crack-flash, then scatter-to-rest
    var phase = 0;                     // 0 crack, 1 scatter, 2 hang, 3 reassemble
    var raf = 0, t0 = performance.now(), lastT = 0, homeT = 0;
    el.style.transition = 'none';

    // Reached by reassembly's touchdown OR immediately on scroll — the shards
    // hang INDEFINITELY on a fixed canvas anchored to the equation's
    // trigger-time position, so a scrolled page floats glass over the wrong
    // content (Charles, 2026-07-06). Scroll mid-shatter = the equation simply
    // returns whole, instantly.
    var cleanup = scrollKillable(function () {
      if (raf) { cancelAnimationFrame(raf); raf = 0; }
      canvas.remove();
      el.style.opacity = op0 || '1';
      el.style.transition = trans0;
      el.__lxShatter = null;
    });
    el.__lxShatter = function () {
      if (phase !== 2) { return; }
      for (var i = 0; i < shards.length; i++) {
        var s = shards[i];
        s.hx = s.dx; s.hy = s.dy; s.hr = s.rot; // reassembly start pose
      }
      phase = 3; homeT = 0;
    };

    // glassFade (0..1) dims the pane tint + edge lines — 1 while broken/hanging,
    // easing to 0 during reassembly so the crack-web never lingers on the
    // healed equation (Charles smoke feedback 2026-07-06).
    function drawShard(s, glassFade) {
      ctx.save();
      ctx.translate(r.left + s.mx + s.dx, r.top + s.my + s.dy);
      ctx.rotate(s.rot);
      ctx.beginPath();
      ctx.moveTo(s.tri[0][0] - s.mx, s.tri[0][1] - s.my);
      ctx.lineTo(s.tri[1][0] - s.mx, s.tri[1][1] - s.my);
      ctx.lineTo(s.tri[2][0] - s.mx, s.tri[2][1] - s.my);
      ctx.closePath();
      // Glass body: clipped ink + a faint pane tint + a bright edge.
      ctx.save();
      ctx.clip();
      if (glassFade > 0.01) {
        ctx.fillStyle = 'rgba(190,225,255,' + (0.10 * glassFade).toFixed(3) + ')';
        ctx.fill();
      }
      ctx.drawImage(pane, -s.mx, -s.my, r.width, r.height);
      ctx.restore();
      if (glassFade > 0.01) {
        ctx.strokeStyle = 'rgba(220,240,255,' + (0.35 * glassFade).toFixed(3) + ')';
        ctx.lineWidth = 0.8;
        ctx.stroke();
      }
      ctx.restore();
    }

    function frame(now) {
      var e = now - t0;
      var dt = lastT ? Math.min(0.05, (now - lastT) / 1000) : 0.016;
      lastT = now;
      ctx.clearRect(0, 0, vw, vh);
      if (phase === 0) {
        // Crack-web flash: the mesh edges light up over the still-visible pane.
        var a = Math.min(1, e / 60);
        ctx.strokeStyle = 'rgba(235,248,255,' + (0.85 * a).toFixed(3) + ')';
        ctx.lineWidth = 1;
        for (var i = 0; i < shards.length; i += 2) {
          var s = shards[i];
          ctx.beginPath();
          ctx.moveTo(r.left + s.tri[0][0], r.top + s.tri[0][1]);
          ctx.lineTo(r.left + s.tri[1][0], r.top + s.tri[1][1]);
          ctx.lineTo(r.left + s.tri[2][0], r.top + s.tri[2][1]);
          ctx.closePath();
          ctx.stroke();
        }
        if (e >= CRACK) { phase = 1; el.style.opacity = '0'; }
        raf = requestAnimationFrame(frame);
        return;
      }
      if (phase === 1) {
        // Zero-g scatter: pure drag decays the break impulse to a drift.
        var drag = Math.pow(0.14, dt);
        for (var k = 0; k < shards.length; k++) {
          var p = shards[k];
          p.dx += p.vx * dt; p.dy += p.vy * dt; p.rot += p.vr * dt;
          p.vx *= drag; p.vy *= drag; p.vr *= Math.pow(0.5, dt);
        }
        if (e >= CRACK + BREAK) { phase = 2; }
      } else if (phase === 2) {
        // Hang: a slow weightless bob until the next click.
        var tt = e / 1000;
        for (var m = 0; m < shards.length; m++) {
          var q = shards[m];
          q.dy += Math.sin(tt * q.bobW + q.bobP) * q.bobA * dt;
          q.rot += q.vr * 0.08 * dt;
        }
      } else if (phase === 3) {
        // Magnetic reassembly: ease every shard from its captured pose to home.
        homeT += dt;
        var g = Math.min(1, homeT / 0.55);
        var hold = Math.pow(1 - g, 3); // remaining offset: fast pull, gentle landing
        for (var n = 0; n < shards.length; n++) {
          var w = shards[n];
          w.dx = w.hx * hold; w.dy = w.hy * hold; w.rot = w.hr * hold;
        }
        // CUT OVER as soon as the shards are visually home — the ease means that
        // happens well before g==1, and holding the crack-web on a healed pane
        // reads as a stall (Charles smoke, 2026-07-06). The real element returns
        // in the same frame the canvas dies: no lingering mesh, no gap.
        if (hold <= 0.02) { cleanup(); return; }
        for (var d3 = 0; d3 < shards.length; d3++) {
          // Glass fades with the pull so edge lines are gone by touchdown.
          drawShard(shards[d3], Math.min(1, hold * 3));
        }
        raf = requestAnimationFrame(frame);
        return;
      }
      for (var d = 0; d < shards.length; d++) { drawShard(shards[d], 1); }
      raf = requestAnimationFrame(frame);
    }
    raf = requestAnimationFrame(frame);
  }

  // SPARKLER: a white-hot spark travels along every glyph stroke, writing the
  // equation in fire — embers spray off the moving tip, drift, and die as the
  // letters cool into place. Handscribe's dashoffset draw-on, driven manually
  // per frame so a body-canvas ember tip can ride the exact frontier (mapped
  // through getScreenCTM); presentation attributes only on the existing
  // <path>s — nothing is added to the inner <svg>.
  function sparkler(el) {
    if (el.__lxSparkler) { return; }
    var svg = el.querySelector('svg');
    if (!svg) { return; }
    var paths = Array.prototype.slice.call(svg.querySelectorAll('path'));
    if (!paths.length || reduced) { return; } // reduced motion: leave it static
    el.__lxSparkler = true;
    paths.sort(function (a, b) {
      try { return a.getBBox().x - b.getBBox().x; } catch (e) { return 0; }
    });
    var DRAW = 420, STAGGER = 70, FILL = 300, COOL = 700;
    var glyphs = [];
    paths.forEach(function (p, i) {
      var len;
      try { len = p.getTotalLength() || 100; } catch (e) { len = 100; }
      p.style.stroke = '#ffb25e';
      p.style.strokeWidth = '1.5';
      p.style.fill = 'transparent';
      p.style.strokeDasharray = len;
      p.style.strokeDashoffset = len;
      glyphs.push({ p: p, len: len, start: i * STAGGER, done: false });
    });
    var total = (glyphs.length - 1) * STAGGER + DRAW;

    var vw = window.innerWidth, vh = window.innerHeight;
    var dpr = window.devicePixelRatio || 1;
    var canvas = document.createElement('canvas');
    canvas.width = Math.round(vw * dpr);
    canvas.height = Math.round(vh * dpr);
    canvas.style.cssText = 'position:fixed;left:0;top:0;width:100vw;height:100vh;'
      + 'pointer-events:none;z-index:2147483647;';
    document.body.appendChild(canvas);
    var ctx = canvas.getContext('2d');
    ctx.scale(dpr, dpr);

    var fil0 = el.style.filter, trans0 = el.style.transition;
    el.style.filter = 'drop-shadow(0 0 7px rgba(255,150,60,0.65))';
    var embers = [], raf = 0, timers = [], t0 = performance.now(), lastT = 0;

    var cleanup = scrollKillable(function () { // + ends on scroll
      if (raf) { cancelAnimationFrame(raf); raf = 0; }
      timers.forEach(clearTimeout); timers.length = 0;
      canvas.remove();
      el.style.filter = fil0; el.style.transition = trans0;
      // Mid-draw glyphs are stroke-dashed with a transparent fill — a scroll
      // teardown here must restore every path to the pristine filled glyph or
      // the equation stays half-invisible (unlike the pure-overlay effects).
      glyphs.forEach(function (g) {
        var p = g.p;
        p.style.stroke = ''; p.style.strokeWidth = '';
        p.style.strokeDasharray = ''; p.style.strokeDashoffset = '';
        p.style.transition = ''; p.style.fill = '';
      });
      el.__lxSparkler = false;
    });

    function tipAt(g, prog) {
      // The draw frontier in page coordinates: dashoffset len→0 reveals from
      // the path's start, so the visible tip is at length len*prog.
      var pt, m;
      try {
        pt = g.p.getPointAtLength(g.len * prog);
        m = g.p.getScreenCTM();
      } catch (e) { return null; }
      if (!m) { return null; }
      return { x: m.a * pt.x + m.c * pt.y + m.e,
               y: m.b * pt.x + m.d * pt.y + m.f };
    }

    var PALETTE = ['#ffffff', '#ffe9b0', '#ffc46a', '#ff9d3a', '#ff5f2e'];
    function frame(now) {
      var e = now - t0;
      var dt = lastT ? Math.min(0.05, (now - lastT) / 1000) : 0.016;
      lastT = now;
      ctx.clearRect(0, 0, vw, vh);
      ctx.globalCompositeOperation = 'lighter';
      for (var i = 0; i < glyphs.length; i++) {
        var g = glyphs[i];
        var prog = Math.max(0, Math.min(1, (e - g.start) / DRAW));
        if (prog <= 0) { continue; }
        g.p.style.strokeDashoffset = String(g.len * (1 - prog));
        if (prog >= 1 && !g.done) {
          g.done = true;
          // Cool the finished glyph: warm stroke fades as the ink fill takes.
          (function (p) {
            p.style.transition = 'fill ' + FILL + 'ms ease, stroke ' + FILL + 'ms ease';
            p.style.fill = 'currentColor';
            p.style.stroke = 'transparent';
            timers.push(setTimeout(function () {
              p.style.stroke = ''; p.style.strokeWidth = '';
              p.style.strokeDasharray = ''; p.style.strokeDashoffset = '';
              p.style.transition = ''; p.style.fill = '';
            }, FILL + 80));
          })(g.p);
        }
        if (prog < 1) {
          var tip = tipAt(g, prog);
          if (tip) {
            // White-hot tip glow.
            var tg = ctx.createRadialGradient(tip.x, tip.y, 0, tip.x, tip.y, 7);
            tg.addColorStop(0, 'rgba(255,255,255,0.95)');
            tg.addColorStop(0.5, 'rgba(255,200,110,0.55)');
            tg.addColorStop(1, 'rgba(255,140,40,0)');
            ctx.fillStyle = tg;
            ctx.beginPath(); ctx.arc(tip.x, tip.y, 7, 0, Math.PI * 2); ctx.fill();
            // Spray a few embers off the tip.
            for (var s = 0; s < 3; s++) {
              var ang = Math.random() * Math.PI * 2;
              var sp = 20 + Math.random() * 90;
              embers.push({ x: tip.x, y: tip.y,
                vx: Math.cos(ang) * sp, vy: Math.sin(ang) * sp - 35,
                life: 0.5 + Math.random() * 0.6,
                size: 0.6 + Math.random() * 1.4,
                col: PALETTE[(Math.random() * PALETTE.length) | 0] });
            }
          }
        }
      }
      // Integrate + draw the embers (light gravity, fade out).
      var alive = 0;
      for (var k = 0; k < embers.length; k++) {
        var em = embers[k];
        if (em.life <= 0) { continue; }
        alive++;
        em.x += em.vx * dt; em.y += em.vy * dt;
        em.vy += 130 * dt; em.life -= dt * 1.1;
        var a = Math.max(0, Math.min(1, em.life));
        ctx.beginPath();
        ctx.fillStyle = em.col;
        ctx.globalAlpha = a;
        ctx.arc(em.x, em.y, em.size, 0, Math.PI * 2);
        ctx.fill();
        ctx.globalAlpha = 1;
      }
      if (e > total + COOL && alive === 0) {
        el.style.transition = 'filter 400ms ease';
        el.style.filter = fil0 || 'none';
        timers.push(setTimeout(cleanup, 420));
        return;
      }
      raf = requestAnimationFrame(frame);
    }
    raf = requestAnimationFrame(frame);
  }

  // QUANTUM: the equation sits in superposition — every glyph jitters fuzzily
  // between ghost positions under a soft blur, until you OBSERVE it (hover):
  // the wavefunction collapses crisp with a snap-flash. Idle-collapses on its
  // own after a while. Inline transform/filter on the existing <path>s + a
  // container flash; nothing is added to the inner <svg>.
  function quantum(el) {
    if (el.__lxQuantum) { return; }
    var svg = el.querySelector('svg');
    if (!svg) { return; }
    var paths = Array.prototype.slice.call(svg.querySelectorAll('path'));
    if (!paths.length || reduced) { return; }
    el.__lxQuantum = true;
    var IDLE_COLLAPSE = 12000;
    var raf = 0, timers = [], t0 = performance.now(), collapsed = false;

    function collapse() {
      if (collapsed) { return; }
      collapsed = true;
      if (raf) { cancelAnimationFrame(raf); raf = 0; }
      timers.forEach(clearTimeout); timers.length = 0;
      paths.forEach(function (p) {
        p.style.transition = 'transform 140ms cubic-bezier(.2,1.6,.4,1), filter 140ms ease';
        // Snap home = zero DELTA composed with placement — never 'none', which
        // would strip the placement attribute and blob the glyph mid-collapse.
        setPathDelta(p, 'translate(0px,0px)');
        p.style.filter = 'none';
      });
      // Observation snap-flash on the container.
      var fil0 = el.style.filter, trans0 = el.style.transition;
      el.style.transition = 'filter 90ms ease';
      el.style.filter = 'brightness(1.9) drop-shadow(0 0 8px currentColor)';
      setTimeout(function () {
        el.style.filter = fil0 || 'none';
        setTimeout(function () {
          el.style.transition = trans0;
          paths.forEach(function (p) {
            p.style.transition = ''; p.style.transform = ''; p.style.filter = '';
          });
          el.__lxQuantum = false;
        }, 200);
      }, 110);
      el.removeEventListener('mouseenter', collapse);
    }

    function frame(now) {
      if (collapsed) { return; }
      if (now - t0 > IDLE_COLLAPSE) { collapse(); return; }
      for (var i = 0; i < paths.length; i++) {
        var p = paths[i];
        var dx = (Math.random() * 2 - 1) * 1.6;
        var dy = (Math.random() * 2 - 1) * 1.2;
        setPathDelta(p, 'translate(' + dx.toFixed(2) + 'px,' + dy.toFixed(2) + 'px)');
        p.style.filter = 'blur(' + (0.4 + Math.random() * 0.5).toFixed(2) + 'px)'
          + ' opacity(' + (0.72 + Math.random() * 0.28).toFixed(2) + ')';
      }
      // Superposition flickers at ~20fps, not every frame — fuzzier that way.
      timers.push(setTimeout(function () { raf = requestAnimationFrame(frame); }, 50));
    }
    el.addEventListener('mouseenter', collapse);
    raf = requestAnimationFrame(frame);
  }

  // TYPESET: letterpress — the glyphs stamp onto the page one by one in reading
  // order, each pressed in with a satisfying squash. Inline opacity/transform on
  // the existing <path>s (transform-box: fill-box so each squashes about its own
  // centre); nothing is added to the inner <svg>.
  function typeset(el) {
    if (el.__lxTypeset) { return; }
    var svg = el.querySelector('svg');
    if (!svg) { return; }
    var paths = Array.prototype.slice.call(svg.querySelectorAll('path'));
    if (!paths.length || reduced) { return; }
    el.__lxTypeset = true;
    paths.sort(function (a, b) {
      try { return a.getBBox().x - b.getBBox().x; } catch (e) { return 0; }
    });
    var STAGGER = 90, PRESS = 160;
    // Pivot-scale about each glyph's own user-space centre, composed with its
    // placement (the transform-box pattern stomps the placement attribute).
    paths.forEach(function (p) {
      p.style.opacity = '0';
      setPathDelta(p, pivotScaleDelta(p, 1.35));
    });
    var timers = [];
    paths.forEach(function (p, i) {
      timers.push(setTimeout(function () {
        p.style.transition = 'opacity ' + PRESS + 'ms ease-out, transform '
          + PRESS + 'ms cubic-bezier(.3,1.4,.5,1)';
        p.style.opacity = '1';
        setPathDelta(p, pivotScaleDelta(p, 1));
        timers.push(setTimeout(function () {
          p.style.transition = ''; p.style.opacity = '';
          clearPathDelta(p);
          if (i === paths.length - 1) { el.__lxTypeset = false; }
        }, PRESS + 80));
      }, i * STAGGER));
    });
  }

  // ---- constellation bounds (plan 62fafe76, LTX-10) ------------------------
  // A GLOBAL star ceiling and a SPATIAL GRID keep the star map cheap regardless
  // of how many glyph <path>s the equation has. Without them a dense equation
  // sampled 3-14 stars/path (unbounded total) and then scanned EVERY star
  // against EVERY other to find neighbours — O(S^2) setup that grows with the
  // square of the glyph count.
  var STAR_BUDGET = 240;      // total stars, whatever the path count
  var LINK_DIST = 42;         // a constellation line joins stars closer than this
  var LINK_D2 = LINK_DIST * LINK_DIST;
  function starCmp(a, b) { return a[0] - b[0]; }

  // Sample stars along the glyph outlines under a global budget. Within budget the
  // per-path count is the SAME formula as before, so a normal equation's star map
  // is byte-for-byte unchanged; over budget every path's count scales down
  // proportionally (a faithful, evenly-distributed downsample — never a
  // left-biased truncation), and a final even stride is the hard ceiling for the
  // pathological path-count case where even one-star-per-path would overflow.
  function buildStars(paths) {
    var specs = [], totalDesired = 0;
    for (var pi = 0; pi < paths.length; pi++) {
      var p = paths[pi], len, m;
      try { len = p.getTotalLength(); m = p.getScreenCTM(); } catch (e) { continue; }
      if (!len || !m) { continue; }
      var n = Math.max(3, Math.min(14, Math.round(len / 26)));
      specs.push({ p: p, len: len, m: m, n: n });
      totalDesired += n;
    }
    var ratio = totalDesired > STAR_BUDGET ? STAR_BUDGET / totalDesired : 1;
    var stars = [];
    for (var si = 0; si < specs.length; si++) {
      var sp = specs[si];
      var n2 = ratio < 1 ? Math.max(1, Math.floor(sp.n * ratio)) : sp.n;
      for (var i = 0; i < n2; i++) {
        var pt;
        try { pt = sp.p.getPointAtLength((i / n2) * sp.len); } catch (e) { continue; }
        stars.push({ x: sp.m.a * pt.x + sp.m.c * pt.y + sp.m.e,
                     y: sp.m.b * pt.x + sp.m.d * pt.y + sp.m.f,
                     tw: Math.random() * 6.283,
                     ignite: Math.random() * 700 });
      }
    }
    if (stars.length > STAR_BUDGET) {   // hard ceiling: even stride across the map
      var kept = [], step = stars.length / STAR_BUDGET;
      for (var k = 0; k < STAR_BUDGET; k++) { kept.push(stars[Math.floor(k * step)]); }
      stars = kept;
    }
    return stars;
  }

  // Join each star to its 2 nearest neighbours via a LINK_DIST-sized spatial grid:
  // a star's neighbours within LINK_DIST are guaranteed to fall in its own or an
  // adjacent cell, so scanning the 3x3 cell block finds every candidate that could
  // become a link — near-linear in star count, and the resulting link SET is
  // identical to the old all-pairs scan (every kept link is < LINK_DIST, and all
  // sub-LINK_DIST candidates live in the block, so the 2-nearest ranking agrees).
  function linkStars(stars) {
    var grid = {}, i;
    function cell(x, y) { return Math.floor(x / LINK_DIST) + ',' + Math.floor(y / LINK_DIST); }
    for (i = 0; i < stars.length; i++) {
      var ck = cell(stars[i].x, stars[i].y);
      (grid[ck] = grid[ck] || []).push(i);
    }
    var links = [], seen = {};
    for (i = 0; i < stars.length; i++) {
      var s = stars[i];
      var gx = Math.floor(s.x / LINK_DIST), gy = Math.floor(s.y / LINK_DIST);
      var best = [];
      for (var ox = -1; ox <= 1; ox++) {
        for (var oy = -1; oy <= 1; oy++) {
          var bucket = grid[(gx + ox) + ',' + (gy + oy)];
          if (!bucket) { continue; }
          for (var bi = 0; bi < bucket.length; bi++) {
            var j = bucket[bi];
            if (j === i) { continue; }
            var dx = stars[j].x - s.x, dy = stars[j].y - s.y;
            var d2 = dx * dx + dy * dy;
            if (best.length < 2) { best.push([d2, j]); best.sort(starCmp); }
            else if (d2 < best[1][0]) { best[1] = [d2, j]; best.sort(starCmp); }
          }
        }
      }
      for (var b = 0; b < best.length; b++) {
        var key = Math.min(i, best[b][1]) + ':' + Math.max(i, best[b][1]);
        if (!seen[key] && best[b][0] < LINK_D2) { seen[key] = 1; links.push([i, best[b][1]]); }
      }
    }
    return links;
  }

  // CONSTELLATION: the equation first appears as a night-sky star map — points
  // ignite along the glyph outlines, faint lines join near neighbours, the map
  // twinkles, then the stars fuse into the crisp equation. Star positions are
  // sampled read-only from the existing <path>s (getPointAtLength → screen via
  // getScreenCTM); stars/lines live on a body canvas and only opacity is
  // toggled on the paths — nothing is added to the inner <svg>.
  function constellation(el) {
    if (el.__lxConst) { return; }
    var svg = el.querySelector('svg');
    if (!svg) { return; }
    var paths = Array.prototype.slice.call(svg.querySelectorAll('path'));
    if (!paths.length || reduced) { return; }
    el.__lxConst = true;
    var stars = buildStars(paths);   // global star budget (plan 62fafe76)
    // If star-sampling produced nothing — the container was display:none or
    // detached at load, so getScreenCTM/getTotalLength returned null/0 — the CSS
    // pre-hide (opacity:0) would otherwise leave the equation PERMANENTLY
    // invisible (review MEDIUM). Reveal it plainly and bail: no stars, but the
    // math is visible, which is the correct degrade.
    if (!stars.length) { el.style.opacity = '1'; el.__lxConst = false; return; }
    // Join each star to its 2 nearest neighbours (the constellation lines) via a
    // spatial grid — near-linear in star count, not the old O(S^2) all-pairs scan.
    var links = linkStars(stars);

    var op0 = [], trans0 = [];
    paths.forEach(function (p, i) {
      op0[i] = p.style.opacity; trans0[i] = p.style.transition;
      p.style.opacity = '0';
    });
    // The CSS pre-hide ([data-lx-fx-enter=constellation] { opacity: 0 }) kept the
    // container invisible until this routine took over; the paths now carry the
    // hide, so reveal the container for the star map.
    el.style.opacity = '1';
    var vw = window.innerWidth, vh = window.innerHeight;
    var dpr = window.devicePixelRatio || 1;
    var canvas = document.createElement('canvas');
    canvas.width = Math.round(vw * dpr);
    canvas.height = Math.round(vh * dpr);
    canvas.style.cssText = 'position:fixed;left:0;top:0;width:100vw;height:100vh;'
      + 'pointer-events:none;z-index:2147483647;';
    document.body.appendChild(canvas);
    var ctx = canvas.getContext('2d');
    ctx.scale(dpr, dpr);

    // ONE reusable star sprite (plan 62fafe76): the old code allocated a fresh
    // radial gradient for EVERY visible star on EVERY frame — allocation-heavy at
    // ~60fps. Bake the white-core → blue-edge → transparent falloff once into an
    // offscreen canvas, then blit it per star with ctx.globalAlpha for the
    // twinkle/fade (globalAlpha multiplies the sprite's baked per-pixel alpha, so
    // the on-screen look — alpha, alpha*0.55 at the mid stop — is unchanged).
    var STAR_R = 2.6;
    var sprite = document.createElement('canvas');
    var SS = Math.max(8, Math.round(STAR_R * 2 * dpr * 2));
    sprite.width = SS; sprite.height = SS;
    var spx = sprite.getContext('2d');
    var sc = SS / 2;
    var sg = spx.createRadialGradient(sc, sc, 0, sc, sc, sc);
    sg.addColorStop(0, 'rgba(255,255,255,1)');
    sg.addColorStop(0.5, 'rgba(190,215,255,0.55)');
    sg.addColorStop(1, 'rgba(150,190,255,0)');
    spx.fillStyle = sg;
    spx.fillRect(0, 0, SS, SS);

    var IGNITE = 700, HOLD = 1500, FUSE = 700, END = IGNITE + HOLD + FUSE;
    var raf = 0, timers = [], t0 = performance.now(), fusing = false;
    // Reached by the normal fuse-out OR immediately on scroll — the star map is
    // a fixed canvas sampled at trigger time, so scrolling leaves an
    // after-image at the equation's OLD position (Charles, 2026-07-06). On
    // teardown the paths restore to visible, so a scroll mid-show simply fuses
    // the equation in early.
    var cleanup = scrollKillable(function () {
      if (raf) { cancelAnimationFrame(raf); raf = 0; }
      timers.forEach(clearTimeout); timers.length = 0;
      canvas.remove();
      paths.forEach(function (p, i) {
        p.style.opacity = op0[i] || ''; p.style.transition = trans0[i] || '';
      });
      el.__lxConst = false;
    });
    function frame(now) {
      if (cleanup.dead()) { return; }
      var e = now - t0;
      ctx.clearRect(0, 0, vw, vh);
      var fade = e > IGNITE + HOLD ? Math.max(0, 1 - (e - IGNITE - HOLD) / FUSE) : 1;
      // Constellation lines, faint and steady.
      ctx.strokeStyle = 'rgba(150,190,255,' + (0.20 * fade).toFixed(3) + ')';
      ctx.lineWidth = 0.6;
      for (var l = 0; l < links.length; l++) {
        var a = stars[links[l][0]], b = stars[links[l][1]];
        if (e < a.ignite || e < b.ignite) { continue; }
        ctx.beginPath(); ctx.moveTo(a.x, a.y); ctx.lineTo(b.x, b.y); ctx.stroke();
      }
      // The stars: ignite staggered, then twinkle.
      for (var i = 0; i < stars.length; i++) {
        var s = stars[i];
        if (e < s.ignite) { continue; }
        var born = Math.min(1, (e - s.ignite) / 180);
        var tw = 0.55 + 0.45 * Math.sin(e / 340 + s.tw);
        var alpha = born * tw * fade;
        if (alpha <= 0.01) { continue; }
        ctx.globalAlpha = alpha;                     // twinkle/fade via the blit
        ctx.drawImage(sprite, s.x - STAR_R, s.y - STAR_R, STAR_R * 2, STAR_R * 2);
      }
      ctx.globalAlpha = 1;                           // restore for the next frame's lines
      if (e > IGNITE + HOLD && !fusing) {
        fusing = true; // the stars fuse: equation cross-fades in as the map dims
        paths.forEach(function (p) {
          p.style.transition = 'opacity ' + FUSE + 'ms ease';
          p.style.opacity = '1';
        });
      }
      if (e >= END) { cleanup(); return; }
      raf = requestAnimationFrame(frame);
    }
    raf = requestAnimationFrame(frame);
  }

  // Play a trigger's effect. lightning/storm/handscribe (+ hologram/neonsign/
  // THREAD: the first SEMANTIC effect — hover any glyph and every other occurrence
  // of the same source token lights up while the rest of the equation recedes.
  // Identity arrives via the data-lx-glyphmap container sidecar (RFC lattex/34→35):
  // runs of <hex-codepoint>:<path-index-list>, indices addressing the inner SVG's
  // <path>s in emit order. The map is renderer-derived and value-constrained by the
  // sanitizer; we still validate against the contract grammar here (defense in
  // depth + the drift-guard test keys on this literal). No map → inert. Only
  // opacity/transform presentation attributes on EXISTING paths change.
  var GLYPHMAP_RE = /^[0-9a-f]+:[0-9]+(,[0-9]+)*(;[0-9a-f]+:[0-9]+(,[0-9]+)*)*$/;

  function parseGlyphmap(el, pathCount) {
    var raw = el.getAttribute('data-lx-glyphmap');
    if (!raw || !GLYPHMAP_RE.test(raw)) { return null; }
    var groups = {}; // path index -> [all indices of its token group]
    var runs = raw.split(';');
    for (var r = 0; r < runs.length; r++) {
      var pair = runs[r].split(':');
      var idx = pair[1].split(',').map(Number);
      for (var i = 0; i < idx.length; i++) {
        if (idx[i] >= pathCount) { return null; } // out-of-bounds map: refuse whole
        groups[idx[i]] = idx;
      }
    }
    return groups;
  }

  function thread(el) {
    if (el.__lxThread) { return; }
    var svg = el.querySelector('svg');
    if (!svg) { return; }
    var paths = Array.prototype.slice.call(svg.querySelectorAll('path'));
    var groups = parseGlyphmap(el, paths.length);
    if (!groups) { return; } // no/invalid glyphmap: semantic effects stay inert
    el.__lxThread = true;

    // INDEXED group membership (plan 62fafe76): the old hover called
    // group.indexOf(i) for every path on every mouseenter — a large repeated-token
    // group made each hover approach O(P^2). parseGlyphmap already hands every
    // member of a token group the SAME array reference, so map each path to an
    // integer group id ONCE (Map keyed by that shared reference → O(1) build), and
    // hover is then an O(1) integer compare per path. Same glyphs light as before:
    // members of a run share a reference → share an id, exactly the indexOf set.
    var groupId = new Array(paths.length);
    var idByArr = new Map();
    for (var gi = 0; gi < paths.length; gi++) {
      var grp = groups[gi];
      if (!grp) { groupId[gi] = -1; continue; }
      var id = idByArr.get(grp);
      if (id === undefined) { id = idByArr.size; idByArr.set(grp, id); }
      groupId[gi] = id;
    }

    // NEVER set style.transform on these paths: each carries its PLACEMENT as an
    // SVG transform attribute (translate + font-unit downscale), and the CSS
    // transform property overrides the attribute wholesale — the glyph loses its
    // placement and renders as a font-unit-sized blob (Charles's smoke, 2026-07-06).
    // The emphasis is a bold-stroke instead: stroke-width is in the path's LOCAL
    // (font design) units, so ~28 units reads as a crisp optical bolding at any
    // rendered size, composing with the placement rather than fighting it.
    function restore() {
      for (var i = 0; i < paths.length; i++) {
        paths[i].style.opacity = '';
        paths[i].style.stroke = '';
        paths[i].style.strokeWidth = '';
        paths[i].style.transition = '';
      }
    }
    function light(gid) {
      for (var i = 0; i < paths.length; i++) {
        var mate = groupId[i] === gid;    // O(1) indexed membership, was indexOf
        var p = paths[i];
        p.style.transition = 'opacity 140ms ease';
        p.style.opacity = mate ? '1' : '0.22';
        p.style.stroke = mate ? 'currentColor' : '';
        p.style.strokeWidth = mate ? '28' : '';
      }
    }
    paths.forEach(function (p, i) {
      if (groupId[i] < 0) { return; } // structural/unmapped glyph: recedes with the rest
      p.style.cursor = 'default';
      p.addEventListener('mouseenter', function () { light(groupId[i]); });
    });
    svg.addEventListener('mouseleave', restore);
  }

  // PRECEDENCE: the order-of-operations cascade — hover and the equation EVALUATES in
  // binding order, sub-expressions lighting inward. Identity arrives via the
  // data-lx-groupmap container sidecar (seam lattex/168→172): runs of
  // <rank>:<path-index-list>, indices addressing the inner SVG's <path>s in emit order,
  // rank = evaluation order (0 = first). Renderer-derived + sanitizer-value-constrained;
  // we still validate the contract grammar here (defense in depth + the drift-guard test
  // keys on this literal) and REFUSE the whole map on any out-of-bounds index (Lattice's
  // boundary note: the runtime rejects an invalid semantic member, never a partial). No
  // map → inert (the whole-expression fail-honest static degrade). Only opacity/transform
  // on EXISTING paths change; adds no element to the inner <svg>.
  var GROUPMAP_RE = /^[0-9]+:[0-9]+(,[0-9]+)*(;[0-9]+:[0-9]+(,[0-9]+)*)*$/;

  function parseGroupmap(el, pathCount) {
    var raw = el.getAttribute('data-lx-groupmap');
    if (!raw || !GROUPMAP_RE.test(raw)) { return null; }
    var runs = raw.split(';');
    var ranks = []; // ascending rank order: ranks[k] = [path indices at that rank]
    var prevRank = -1;
    for (var r = 0; r < runs.length; r++) {
      var pair = runs[r].split(':');
      // The rank (pair[0]) MUST be strictly ascending — the cascade steps runs in array
      // order, so a rank-disordered map (5:1;2:3) would animate the WRONG order. Refuse it
      // wholesale, matching the out-of-bounds posture (Fixpoint N1, lattex/176) rather than
      // silently trusting the producer's serialization.
      var rank = Number(pair[0]);
      if (!(rank > prevRank)) { return null; }
      prevRank = rank;
      var idx = pair[1].split(',').map(Number);
      for (var i = 0; i < idx.length; i++) {
        if (idx[i] >= pathCount) { return null; } // out-of-bounds: refuse whole
      }
      ranks.push(idx);
    }
    if (ranks.length < 2) { return null; } // nothing to sequence: inert
    return ranks;
  }

  function precedence(el) {
    if (el.__lxPrecedence) { return; }
    var svg = el.querySelector('svg');
    if (!svg) { return; }
    var paths = Array.prototype.slice.call(svg.querySelectorAll('path'));
    var ranks = parseGroupmap(el, paths.length);
    if (!ranks) { return; } // no/invalid/single-rank groupmap: stays inert (static)
    el.__lxPrecedence = true;

    // Bold-stroke emphasis in the path's LOCAL (font design) units — never style.transform,
    // which would clobber the per-glyph placement transform attribute (the thread lesson).
    function clear() {
      for (var i = 0; i < paths.length; i++) {
        paths[i].style.opacity = '';
        paths[i].style.stroke = '';
        paths[i].style.strokeWidth = '';
        paths[i].style.transition = '';
      }
    }
    // Light every path in ranks[0..upto] as "resolved" (bold), the rest receded.
    function lightUpTo(upto) {
      var lit = {};
      for (var k = 0; k <= upto && k < ranks.length; k++) {
        for (var j = 0; j < ranks[k].length; j++) { lit[ranks[k][j]] = 1; }
      }
      for (var i = 0; i < paths.length; i++) {
        var on = lit[i] === 1;
        paths[i].style.transition = 'opacity 160ms ease, stroke-width 160ms ease';
        paths[i].style.opacity = on ? '1' : '0.25';
        paths[i].style.stroke = on ? 'currentColor' : '';
        paths[i].style.strokeWidth = on ? '28' : '';
      }
    }
    var timers = [];
    function cascade() {
      for (var t = 0; t < timers.length; t++) { clearTimeout(timers[t]); }
      timers = [];
      if (reduced) { lightUpTo(0); return; } // reduced-motion: just show the innermost group
      for (var k = 0; k < ranks.length; k++) {
        (function (step) {
          timers.push(setTimeout(function () { lightUpTo(step); }, step * 520));
        })(k);
      }
    }
    svg.addEventListener('mouseenter', cascade);
    // Handle for autoplay (fx.enter=precedence) and the public LatteXFx.play()
    // trigger (plan 51051447): the cascade is otherwise reachable ONLY by a real
    // pointer crossing into the svg, which locks out docs pages, capture tooling,
    // and touch devices entirely.
    el.__lxPrecedencePlay = cascade;
    svg.addEventListener('mouseleave', function () {
      for (var t = 0; t < timers.length; t++) { clearTimeout(timers[t]); }
      timers = [];
      clear();
    });
  }

  // CANCEL: the third SEMANTIC effect — matching factors strike out and puff away to a
  // grayed ghost. Reuses the SAME data-lx-glyphmap sidecar thread reads (zero new
  // attribute): a source code point that occurs EXACTLY TWICE is a cancelling pair. The
  // routine draws a diagonal strike across each cancelled glyph on a body-level overlay
  // (position:fixed, pointer-events:none, tagged data-lx-fx-overlay=cancel) echoing the
  // author \cancel filled-polygon look — NOT inside the inner <svg>, so the containment
  // contract holds — then puffs the glyphs: opacity fades toward a faint grayed ghost
  // (~0.18) with a placement-composed scale bump (setPathDelta/pivotScaleDelta, NEVER
  // style.transform, which would clobber each glyph's placement transform attribute), so
  // "cancelled" stays legible rather than leaving a broken-looking bare bar. EXACTLY-TWICE
  // v1: the glyphmap doesn't encode numerator-vs-denominator, so this fires on any code
  // point occurring twice (x+x as well as \frac{x}{x}); a 3+-occurrence group, or unequal
  // multiplicity, is INERT (the whole-expression fail-honest posture). No/invalid map →
  // inert. Restores on replay so LatteXFx.play + re-triggers are idempotent.
  function cancel(el) {
    var svg = el.querySelector('svg');
    if (!svg) { return; }
    var paths = Array.prototype.slice.call(svg.querySelectorAll('path'));
    var groups = parseGlyphmap(el, paths.length);
    if (!groups) { return; } // no/invalid glyphmap: semantic effects stay inert

    // The cancelled set: every path whose code-point group has EXACTLY two members. A
    // 3+-member group (x+x+x) or an unequal-multiplicity glyph (x^2/x, where x appears
    // three times) never lands in an exactly-2 group, so it degrades to inert honestly.
    var struck = [], seen = {};
    for (var i = 0; i < paths.length; i++) {
      var g = groups[i];
      if (g && g.length === 2 && !seen[i]) { struck.push(i); seen[i] = 1; }
    }
    if (!struck.length) { return; } // nothing pairs exactly twice: inert

    // Idempotent replay: tear down any prior run (overlay + path styles) before a fresh
    // one, so LatteXFx.play and re-triggers don't stack overlays or double-gray.
    if (el.__lxCancelRestore) { el.__lxCancelRestore(); }

    var color = resolveColor(el);
    var GHOST = '0.18';                         // faint grayed "cancelled" end-state
    var svgNS = 'http://www.w3.org/2000/svg';
    var vw = window.innerWidth, vh = window.innerHeight;

    // A fixed, pointer-events-none body <svg> overlay for the strikes. width/height in px
    // with no viewBox ⇒ 1 user unit = 1 CSS px, and position:fixed at (0,0) means a path's
    // getBoundingClientRect() viewport coords ARE the overlay's coordinate space. Tagged so
    // the lifecycle tests can find + teardown it; never added to the inner math <svg>.
    var overlay = document.createElementNS(svgNS, 'svg');
    overlay.setAttribute('data-lx-fx-overlay', 'cancel');
    overlay.setAttribute('width', vw);
    overlay.setAttribute('height', vh);
    overlay.setAttribute('fill', 'none');
    overlay.style.cssText = 'position:fixed;left:0;top:0;width:100vw;height:100vh;'
      + 'pointer-events:none;z-index:2147483646;overflow:visible;';
    document.body.appendChild(overlay);

    // One diagonal strike per cancelled glyph, from bottom-left to top-right of its screen
    // rect (the \cancel direction), round-capped and sized to the glyph so it reads as a
    // struck-through factor. strokeDasharray/offset primes a draw-on (cleared in reduced).
    var lines = [];
    struck.forEach(function (idx) {
      var r = paths[idx].getBoundingClientRect();
      if (!r.width || !r.height) { return; } // hidden/zero-box glyph: no strike, still puffs
      var pad = Math.max(2, r.height * 0.12);
      var x1 = r.left - pad, y1 = r.bottom + pad, x2 = r.right + pad, y2 = r.top - pad;
      var line = document.createElementNS(svgNS, 'line');
      line.setAttribute('x1', x1.toFixed(2)); line.setAttribute('y1', y1.toFixed(2));
      line.setAttribute('x2', x2.toFixed(2)); line.setAttribute('y2', y2.toFixed(2));
      line.setAttribute('stroke', color);
      line.setAttribute('stroke-width', Math.max(2, r.height * 0.13).toFixed(2));
      line.setAttribute('stroke-linecap', 'round');
      var len = Math.sqrt((x2 - x1) * (x2 - x1) + (y2 - y1) * (y2 - y1));
      line.style.strokeDasharray = len;
      line.style.strokeDashoffset = len;
      overlay.appendChild(line);
      lines.push(line);
    });

    var timers = [];
    function later(fn, ms) { timers.push(setTimeout(fn, ms)); }

    // ONE shared exit for every way the show ends — a fresh replay, a scroll,
    // and (on the animated path) normal completion — routed through
    // scrollKillable exactly like lightning's bolts. The strikes live on a
    // position:fixed body <svg> drawn from the glyphs' TRIGGER-TIME rects: scroll
    // the page and they'd float over unrelated content, and the reduced static
    // overlay (which has no timer) would hang there forever (Charles's smoke
    // sweep, 2026-07-06). So a scroll fires die(), sharing the teardown with the
    // normal end. `scrollAbort` picks the glyph end-state at that exit: on a
    // scroll or a replay it stays true → a TOTAL restore (overlay gone, pending
    // puff timers cleared, glyph opacity/transform back to pristine) so a replay
    // re-triggers cleanly, matching lightning's total-abort; normal completion
    // sets it false to KEEP the grayed "cancelled" ghost — this effect's
    // deliverable — while still going through die() to clear the (spent) timers,
    // drop the overlay, and release the scroll listener.
    var scrollAbort = true;
    function teardown() {
      for (var t = 0; t < timers.length; t++) { clearTimeout(timers[t]); }
      timers = [];
      overlay.remove(); // idempotent — safe whether or not the puff already removed it
      struck.forEach(function (idx) {
        var p = paths[idx];
        p.style.transition = '';
        clearPathDelta(p);
        if (scrollAbort) { p.style.opacity = ''; } // scroll/replay: un-gray to pristine
      });                                          // normal end: leave the grayed ghost
      el.__lxCancelRestore = null;
    }
    var die = scrollKillable(teardown);
    el.__lxCancelRestore = die;

    // Reduced motion: no draw-on, no puff frames — snap to the static end-state (strike
    // fully drawn + glyphs grayed), the precedence lightUpTo(0) posture. die() (armed
    // above) is the ONLY teardown on this path — there are no timers, so a scroll is what
    // removes the otherwise-permanent fixed overlay (and restores the glyphs to pristine).
    if (reduced) {
      lines.forEach(function (l) { l.style.strokeDashoffset = '0'; });
      struck.forEach(function (idx) { paths[idx].style.opacity = GHOST; });
      return;
    }

    var DRAW = 200, HOLD = 240, PUFF_UP = 150, PUFF_SET = 260, FADE = 300;
    // 1) Draw the strikes on.
    requestAnimationFrame(function () {
      lines.forEach(function (l) {
        l.style.transition = 'stroke-dashoffset ' + DRAW + 'ms ease';
        l.style.strokeDashoffset = '0';
      });
    });
    // 2) After the strike lands + a held beat: PUFF — a quick scale bump...
    later(function () {
      struck.forEach(function (idx) {
        var p = paths[idx];
        p.style.transition = 'transform ' + PUFF_UP + 'ms ease';
        setPathDelta(p, pivotScaleDelta(p, 1.24)); // composes with placement, not over it
      });
      // 3) ...then settle back to placement scale while fading to the grayed ghost, and
      //    fade the strike out so only the ghost remains.
      later(function () {
        struck.forEach(function (idx) {
          var p = paths[idx];
          p.style.transition = 'transform ' + PUFF_SET + 'ms ease, opacity ' + PUFF_SET + 'ms ease';
          setPathDelta(p, pivotScaleDelta(p, 1)); // identity scale = back to placement
          p.style.opacity = GHOST;
        });
        overlay.style.transition = 'opacity ' + FADE + 'ms ease';
        overlay.style.opacity = '0';
        // Normal end: the SAME shared exit as a scroll-abort, but keep the ghost.
        later(function () { scrollAbort = false; die(); }, FADE + 40);
      }, PUFF_UP);
    }, DRAW + HOLD);
  }

  // ---- unfold: a bounded \sum blooms into its explicit terms --------------
  // The expanded form is PRE-RENDERED by LatteX into a hidden sibling <svg>
  // wrapped in `.lx-fx-expanded` inside the same .lx-math span (the runtime cannot
  // lay out LaTeX). unfold(el) TOGGLES between the collapsed sum and that payload.
  //
  // ELEMENT-ANCHORED by construction: the payload is a sibling INSIDE the span, so
  // it rides scroll/reflow for free. Unlike fx.cancel — whose strike is a
  // position:fixed BODY overlay drawn from trigger-time getBoundingClientRect() and
  // MUST be torn down on scroll (scrollKillable) — unfold has NO fixed/body overlay,
  // so it needs NO scrollKillable. Do not bolt a fixed overlay onto it.
  //
  // Idempotent: a per-element flag (el.__lxUnfolded) tracks state so re-click /
  // LatteXFx.play re-entry cannot desync. If the element carries no pre-rendered
  // payload (host flag was off, or the sum was unsupported → inert), unfold is a no-op.
  function unfold(el) {
    var collapsed = el.querySelector(':scope > svg');
    var expanded = el.querySelector(':scope > .lx-fx-expanded');
    if (!collapsed || !expanded) { return; } // inert: no payload to arm

    var toExpanded = !el.__lxUnfolded;
    var show = toExpanded ? expanded : collapsed;
    var hide = toExpanded ? collapsed : expanded;
    el.__lxUnfolded = toExpanded;

    // Swap: hide the outgoing immediately (no side-by-side reflow) and reveal the
    // incoming. A held `hidden` attr survives on the payload until now (no flash of
    // both states before the JS runs); clear it to show. `.lx-fx-expanded` is
    // display:none in the CSS, so an explicit inline-block is needed to reveal it.
    hide.hidden = true;
    hide.style.display = 'none';
    show.hidden = false;
    show.style.display = 'inline-block';

    if (reduced) {
      // Reduced motion: instant, no fade (symmetric collapse).
      show.style.opacity = '1';
      return;
    }
    // Fade the incoming in — a whole-expression bloom, element-anchored so it rides
    // scroll. (A true simultaneous cross-fade would need absolute stacking; the
    // fade-in swap is robust and reads as a bloom. Staggered sprout is DEFERRED.)
    show.style.opacity = '0';
    show.style.transition = 'opacity 220ms ease';
    var raf = window.requestAnimationFrame || function (f) { return setTimeout(f, 16); };
    raf(function () { show.style.opacity = '1'; });
  }

  // CASCADE: an ENTER effect for a STACKED, multi-row block (aligned / cases /
  // matrix / an array of equations) — the rows REVEAL one at a time, top to
  // bottom, a beat between them, so a derivation reads as reasoning unfolding line
  // by line. OPACITY-ONLY: it never sets style.transform on the placed paths (the
  // CSS transform property clobbers each glyph's placement transform ATTRIBUTE —
  // the blob bug Charles smoke-caught twice), so the reveal is a per-band opacity
  // ramp and nothing moves.
  //
  // Rows are found by PURE DOM GEOMETRY on the ALREADY-RENDERED inner <svg>: cluster
  // the glyph <path>s by their baseline-y (the placement transform's ty) into
  // horizontal bands. It reads NO container attribute, NO renderer sidecar, NO
  // glyphmap — the geometry already in the SVG is the entire input (runtime-only v1;
  // a renderer-emitted rowmap is the planned fallback for the ambiguous cases below).
  //
  // FAIL-HONEST — the design-critical boundary. Baseline-y ALONE cannot always tell
  // "two rows" from "one tall line": at LatteX's metrics a superscript raises a glyph
  // ~0.36em and a compact matrix/cases row step is ~0.4-0.5em (the SAME magnitude),
  // while a bare \frac stacks numerator/denominator ~1.3em apart — as far as a real
  // display row. So cascade animates ONLY when the band structure is UNAMBIGUOUS:
  //   (1) >= 2 admitted glyphs, (2) a dominant vertical step at ROW scale (rejects a
  //   flat line and script-only spread), (3) every inter-band gap itself a ROW-scale
  //   step >= ROW_MIN_EM of the median glyph em (the em is derived from the placement
  //   y-scale, |sy|*~1000 units-per-em — this is what rejects ~0.36em script offsets),
  //   (4) the between-band gaps cleanly BIMODAL above the within-band spread, and
  //   (5) every band populated (>= MIN_BAND glyphs — rejects a lone script or a
  //   single-glyph \frac). Anything short of ALL of these -> INERT: the content is
  //   shown immediately, fully visible, no animation, no error. Reduced motion -> the
  //   same instant snap. Element-anchored opacity only (no fixed/body overlay), so it
  //   rides scroll for free — no scrollKillable, like unfold.
  function cascade(el) {
    var svg = el.querySelector('svg');
    // Reveal the container FIRST on every path below: a future CSS enter-hide keys on
    // data-lx-fx-enter=cascade, and every exit must leave the math visible (never the
    // CSS-only-invisibility hole where a hidden container is never uncovered).
    el.style.opacity = '1';
    if (!svg) { return; } // no inner svg: nothing to stack -> inert (container shown)
    var all = Array.prototype.slice.call(svg.querySelectorAll('path'));

    // Admit only glyph paths that carry a parseable placement (i.e. a baseline).
    var items = [];
    for (var i = 0; i < all.length; i++) {
      var pl = placement(all[i]);
      if (!pl) { continue; } // no placement -> no baseline -> not bandable
      items.push({ p: all[i], y: pl.ty, s: Math.abs(pl.sy) });
    }
    if (items.length < 2) { return; } // < 2 glyphs -> inert

    // em ~ the font size in USER units: the placement y-scale maps font design units
    // (~1000 per em) to user units, so |sy|*1000 ~ one em. Median over the glyphs is
    // robust to a few smaller script glyphs pulling the scale down.
    var scales = items.map(function (it) { return it.s; })
      .sort(function (a, b) { return a - b; });
    var em = scales[scales.length >> 1] * 1000;
    var ROW_MIN = 0.55 * em; // a genuine baseline-to-baseline row step (> ~0.36em scripts)
    var SPLIT_FRAC = 0.6;    // a band-break gap is >= this fraction of the dominant gap
    var SEP_RATIO = 2.5;     // between-band gaps must clear the within-band spread this much
    var MIN_BAND = 2;        // reject single-glyph bands (a lone script / \frac numerator)

    // Sort baselines; the consecutive gaps carry the band structure.
    var ys = items.map(function (it) { return it.y; })
      .sort(function (a, b) { return a - b; });
    var maxGap = 0, d;
    for (var g = 0; g < ys.length - 1; g++) {
      d = ys[g + 1] - ys[g];
      if (d > maxGap) { maxGap = d; }
    }
    if (maxGap < ROW_MIN) { return; } // no ROW-scale step (flat line / scripts only) -> inert

    // Split into bands wherever a gap is >= the break threshold; track the within-
    // vs between-band gap regimes for the bimodality gate.
    var T = maxGap * SPLIT_FRAC;
    var intraMax = 0, interMin = Infinity;
    for (var k = 0; k < ys.length - 1; k++) {
      d = ys[k + 1] - ys[k];
      if (d >= T) { if (d < interMin) { interMin = d; } }
      else if (d > intraMax) { intraMax = d; }
    }
    if (interMin < ROW_MIN) { return; }                          // every break a real row step
    if (intraMax > 0 && interMin < SEP_RATIO * intraMax) { return; } // cleanly bimodal, not graded

    // Assign a band index to each distinct baseline, then group the glyphs.
    var bandOf = {}, band = 0;
    bandOf[ys[0]] = 0;
    for (var s2 = 1; s2 < ys.length; s2++) {
      if (ys[s2] - ys[s2 - 1] >= T) { band++; }
      bandOf[ys[s2]] = band;
    }
    var bandCount = band + 1;
    if (bandCount < 2) { return; } // needs >= 2 rows to cascade
    var bands = [];
    for (var b2 = 0; b2 < bandCount; b2++) { bands.push([]); }
    for (var m = 0; m < items.length; m++) { bands[bandOf[items[m].y]].push(items[m].p); }
    for (var c = 0; c < bands.length; c++) {
      if (bands[c].length < MIN_BAND) { return; } // a sparse band -> ambiguous -> inert
    }

    // ---- the reveal: opacity-only, band by band, top (smallest y) to bottom ----
    if (reduced) { return; } // container already revealed; glyphs stay fully visible

    if (el.__lxCascade) { return; } // re-entry guard: a replay while running is a no-op
    el.__lxCascade = true;
    var flat = [];
    for (var bi = 0; bi < bands.length; bi++) {
      for (var pj = 0; pj < bands[bi].length; pj++) {
        bands[bi][pj].style.opacity = '0';        // hide, then ramp in per band
        flat.push({ p: bands[bi][pj], band: bi });
      }
    }
    var BEAT = 180, RAMP = 240, lastBand = bands.length - 1;
    // Timeline off performance.now() (a monotone clock) rather than the rAF timestamp
    // so the stagger reads as one continuous ramp; opacity ONLY, no transform.
    var t0 = performance.now();
    function frame() {
      var e = performance.now() - t0;
      for (var f = 0; f < flat.length; f++) {
        var local = e - flat[f].band * BEAT;
        var o = local <= 0 ? 0 : (local >= RAMP ? 1 : local / RAMP);
        flat[f].p.style.opacity = String(o);
      }
      if (e >= lastBand * BEAT + RAMP) {
        for (var q = 0; q < flat.length; q++) { flat[q].p.style.opacity = ''; } // settle pristine
        el.__lxCascade = false;
        return;
      }
      requestAnimationFrame(frame);
    }
    requestAnimationFrame(frame);
  }

  // Play a trigger's effect. lightning/storm/handscribe (+ hologram/neonsign/
  // crystallize/blueprint/wobble/gravwell) → their JS routines;
  // everything else is a one-shot CSS keyframe (reset first so it can replay
  // on re-trigger). (cascade is NOT dispatched here: it is a runtime-only v1 with no
  // Effect enum token yet, so it stays off the parity-guarded name === '...' chain
  // and is driven via the __lxTestHook seam until the authoring wiring lands.)
  function play(el, name, dur) {
    if (name === 'lightning') { lightning(el); return; }
    if (name === 'storm') { storm(el); return; }
    if (name === 'handscribe') { handscribe(el); return; }
    if (name === 'hologram') { hologram(el); return; }
    if (name === 'neonsign') { neonsign(el); return; }
    if (name === 'crystallize') { crystallize(el); return; }
    if (name === 'blueprint') { blueprint(el); return; }
    if (name === 'wobble') { wobble(el); return; }
    if (name === 'gravwell') { gravwell(el); return; }
    if (name === 'matrixrain') { matrixrain(el); return; }
    if (name === 'supernova') { supernova(el); return; }
    if (name === 'inkdrop') { inkdrop(el); return; }
    if (name === 'diffusion') { diffusion(el); return; }
    if (name === 'refraction') { refraction(el); return; }
    if (name === 'teleport') { teleport(el); return; }
    if (name === 'shatter') { shatter(el); return; }
    if (name === 'sparkler') { sparkler(el); return; }
    if (name === 'quantum') { quantum(el); return; }
    if (name === 'typeset') { typeset(el); return; }
    if (name === 'constellation') { constellation(el); return; }
    if (name === 'thread') { thread(el); return; } // arming effect: binds per-glyph hover
    if (name === 'precedence') { precedence(el); return; } // arming: binds the hover cascade
    if (name === 'cancel') { cancel(el); return; } // semantic: strike + puff the exactly-twice pair
    if (name === 'unfold') { unfold(el); return; } // semantic: toggle \sum ⇄ pre-rendered terms
    if (!VOCAB[name] || name === 'none') { return; }
    if (reduced) { return; }
    el.style.animation = 'none';
    void el.offsetWidth; // force reflow so re-assigning replays it
    el.style.animation = 'lx-' + name + ' ' + dur + ' ease both';
  }

  function init() {
    var els = document.querySelectorAll('.lx-math');
    Array.prototype.forEach.call(els, function (el) {
      var enter = el.getAttribute('data-lx-fx-enter');
      var hover = el.getAttribute('data-lx-fx-hover');
      var click = el.getAttribute('data-lx-fx-click');
      var dur = el.getAttribute('data-lx-fx-duration') || '400ms';
      var glowColor = el.getAttribute('data-lx-fx-glow-color');

      // Author-set glow colour drives both the glow keyframe (via the CSS var)
      // and the lightning strike colour.
      if (glowColor) { el.style.setProperty('--lx-glow-color', glowColor); }

      // enter: play once on load.
      if (enter) {
        play(el, enter, dur);
        // Arming effects animate nothing at arm time; enter means "play on load",
        // so fire the cascade once now (reduced-motion is honored inside it).
        if (enter === 'precedence' && el.__lxPrecedencePlay) { el.__lxPrecedencePlay(); }
        // enter=fade holds opacity:1 only via the animation's `both` fill; a
        // LATER transform effect (hover/click) reassigns el.style.animation and
        // drops that hold, so the element would revert to its base opacity:0 and
        // vanish. Pin opacity to 1 once the first animation ends so it can't.
        if (enter === 'fade') {
          el.addEventListener('animationend', function pin() {
            el.style.opacity = '1';
            el.removeEventListener('animationend', pin);
          });
        }
      }
      // hover: play on each mouseenter.
      if (hover) {
        el.addEventListener('mouseenter', function () { play(el, hover, dur); });
      }
      // click: play on each click (and show it's interactive).
      if (click) {
        el.style.cursor = 'pointer';
        el.addEventListener('click', function () { play(el, click, dur); });
      }
    });
  }

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', init);
  } else {
    init();
  }

  // PUBLIC PAGE API (plan 51051447): one supported programmatic trigger, so pages,
  // galleries, capture tooling, and touch UIs can play an element's configured
  // effect without dispatching synthetic pointer events into runtime internals.
  // Exposes ONLY a function taking a DOM element; reads nothing back; adds no
  // new capability beyond what a physical hover already triggers.
  if (typeof window !== 'undefined') {
    window.LatteXFx = window.LatteXFx || {};
    window.LatteXFx.play = function (el) {
      if (!el || !el.getAttribute) { return false; }
      var name = el.getAttribute('data-lx-fx-hover')
              || el.getAttribute('data-lx-fx-enter')
              || el.getAttribute('data-lx-fx-click');
      if (!name) { return false; }
      play(el, name, el.getAttribute('data-lx-fx-duration') || '400ms');
      if (el.__lxPrecedencePlay) { el.__lxPrecedencePlay(); }
      return true;
    };
    // EXPLICIT teardown (plan 62fafe76): the perpetual-work effects (hologram's
    // idle interval, neonsign's flicker chain) are torn down on scroll and when
    // they notice el.isConnected === false, but an embedding SPA that removes an
    // equation in a no-scroll view should be able to end them AT ONCE rather than
    // wait for the next self-check tick. destroy(el) runs whatever teardowns the
    // element owns; each die() is idempotent, so a redundant call is a safe no-op.
    window.LatteXFx.destroy = function (el) {
      if (!el) { return false; }
      var did = false;
      if (el.__lxHoloDie) { el.__lxHoloDie(); did = true; }
      if (el.__lxNeonDie) { el.__lxNeonDie(); did = true; }
      return did;
    };
  }

  // TEST SEAM (fx-runtime JS harness, plan e09b28be): hand the internals to a
  // PRE-INSTALLED hook so the JVM-side GraalJS tests can pin behavior (placement
  // compose, glyphmap grammar, lifecycle teardown). Strictly a no-op in browsers —
  // nothing ever defines window.__lxTestHook there, and the runtime neither reads
  // nor stores anything back from the hook.
  if (typeof window !== 'undefined' && typeof window.__lxTestHook === 'function') {
    window.__lxTestHook({
      placement: placement,
      setPathDelta: setPathDelta,
      clearPathDelta: clearPathDelta,
      userCentre: userCentre,
      pivotScaleDelta: pivotScaleDelta,
      parseGlyphmap: parseGlyphmap,
      buildStars: buildStars,
      linkStars: linkStars,
      STAR_BUDGET: STAR_BUDGET,
      scrollKillable: scrollKillable,
      cancel: cancel,
      unfold: unfold,
      cascade: cascade,
      play: play,
      init: init
    });
  }
})();
