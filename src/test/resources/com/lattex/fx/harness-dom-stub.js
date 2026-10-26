/* Stub DOM for the fx-runtime JS harness (plan e09b28be).
 *
 * Just enough browser surface for lattex-fx.js to LOAD and expose its internals
 * through window.__lxTestHook — deliberately NOT a jsdom: the harness pins unit
 * behavior of extracted internals (placement compose, glyphmap grammar) and the
 * lifecycle contracts (timers cleared, listeners released, overlays removed),
 * not whole-page integration (BrewShot owns real-browser eyeballing).
 *
 * Load this BEFORE lattex-fx.js in the same GraalJS context. The Java side
 * installs window.__lxTestHook to capture the internals object. Set
 * globalThis.__lxStubReducedMotion = true BEFORE loading this stub to boot the
 * runtime in prefers-reduced-motion mode.
 *
 * Deterministic instrumentation the tests drive:
 *   __lxFlushRaf(maxFrames)   — run queued requestAnimationFrame callbacks
 *   __lxRunTimeouts(maxRounds)— run pending setTimeout callbacks (new ones queue)
 *   __lxActiveIntervals()     — count of set-but-not-cleared intervals
 *   __lxActiveTimeouts()      — count of pending (unrun, uncleared) timeouts
 *   __lxScrollListeners()     — count of registered window 'scroll' listeners
 *   __lxFireScroll(dx, dy)    — move the viewport and fire scroll listeners
 *   __lxBodyChildren()        — live count of body-appended overlay elements
 *   __lxWorkSnapshot()       — cumulative DOM/clock/listener/canvas work
 *   __lxSeedRandom(seed)       — deterministic random stream, counted cumulatively
 *   __lxMakeEl(attrs, paths)  — fake element (optionally with inner svg paths)
 */
(function (g) {
  'use strict';

  // CUMULATIVE work, never decremented by teardown or reset between snapshots.
  // Fake fixture construction is excluded; document-created product nodes count.
  var work = {
    elements: 0, canvases: 0, bodyAppends: 0, animations: 0, styleWrites: 0,
    rafScheduled: 0, rafRun: 0, timeouts: 0, intervals: 0, listenerAdds: 0,
    canvasCalls: 0, polylines: 0, segments: 0, strokes: 0, gradients: 0,
    colorStops: 0, arcs: 0, fills: 0, clears: 0, randomCalls: 0
  };
  g.__lxWorkSnapshot = function () { return Object.assign({}, work); };
  g.__lxCanvasStrokes = [];
  g.__lxFilterWrites = [];
  g.__lxGradients = [];
  g.__lxCreatedElements = [];
  g.__lxSeedRandom = function (seed) {
    Math.random = function () {
      work.randomCalls++;
      seed = (Math.imul(seed, 1664525) + 1013904223) >>> 0;
      return seed / 4294967296;
    };
  };

  function classListStub() {
    var set = {};
    return {
      add: function (c) { set[c] = true; },
      remove: function (c) { delete set[c]; },
      contains: function (c) { return !!set[c]; }
    };
  }

  // ---- requestAnimationFrame -------------------------------------------------
  var rafQueue = [];
  g.requestAnimationFrame = function (cb) { work.rafScheduled++; rafQueue.push(cb); return rafQueue.length; };
  g.cancelAnimationFrame = function () {};
  g.__lxFlushRaf = function (maxFrames) {
    var frames = 0;
    while (rafQueue.length && frames < maxFrames) {
      var batch = rafQueue;
      rafQueue = [];
      perfClock += 16;
      for (var i = 0; i < batch.length; i++) { work.rafRun++; batch[i](perfClock); }
      frames++;
    }
    return frames;
  };

  // ---- tracking timers ---------------------------------------------------------
  var nextTimerId = 1;
  var pendingTimeouts = {};  // id -> callback
  var activeIntervals = {};  // id -> callback (never auto-run; tests only count)
  g.setTimeout = function (cb) { work.timeouts++; var id = nextTimerId++; pendingTimeouts[id] = cb; return id; };
  g.clearTimeout = function (id) { delete pendingTimeouts[id]; };
  g.setInterval = function (cb) { work.intervals++; var id = nextTimerId++; activeIntervals[id] = cb; return id; };
  g.clearInterval = function (id) { delete activeIntervals[id]; };
  g.__lxActiveIntervals = function () { return Object.keys(activeIntervals).length; };
  // Drive interval callbacks deterministically (a real interval re-fires until
  // cleared). Each round invokes every still-active interval once; a callback that
  // clearInterval-s itself (e.g. a detached-element self-teardown) stops firing.
  g.__lxRunIntervals = function (maxRounds) {
    var rounds = 0;
    while (rounds < maxRounds && Object.keys(activeIntervals).length) {
      var ids = Object.keys(activeIntervals);
      for (var i = 0; i < ids.length; i++) {
        var cb = activeIntervals[ids[i]];
        if (cb) { cb(); }
      }
      rounds++;
    }
    return rounds;
  };
  g.__lxActiveTimeouts = function () { return Object.keys(pendingTimeouts).length; };
  g.__lxRunTimeouts = function (maxRounds) {
    var rounds = 0;
    while (Object.keys(pendingTimeouts).length && rounds < maxRounds) {
      var batch = pendingTimeouts;
      pendingTimeouts = {};
      for (var id in batch) { batch[id](); }
      rounds++;
    }
    return rounds;
  };

  // ---- scroll viewport + listeners --------------------------------------------
  var scrollListeners = [];
  g.pageXOffset = 0;
  g.pageYOffset = 0;
  g.addEventListener = function (type, fn) {
    work.listenerAdds++;
    if (type === 'scroll') { scrollListeners.push(fn); }
  };
  g.removeEventListener = function (type, fn) {
    if (type !== 'scroll') { return; }
    var i = scrollListeners.indexOf(fn);
    if (i >= 0) { scrollListeners.splice(i, 1); }
  };
  g.__lxScrollListeners = function () { return scrollListeners.length; };
  g.__lxFireScroll = function (dx, dy) {
    g.pageXOffset += dx;
    g.pageYOffset += dy;
    // Iterate a copy: a killed show removes its listener mid-dispatch.
    var fns = scrollListeners.slice();
    for (var i = 0; i < fns.length; i++) { fns[i]({}); }
  };

  g.window = g;
  g.devicePixelRatio = 1;
  g.innerWidth = 1024; g.innerHeight = 768;
  var perfClock = 0;
  g.performance = { now: function () { return perfClock; } };
  var reducedBoot = !!g.__lxStubReducedMotion;
  g.matchMedia = function () {
    return { matches: reducedBoot, addEventListener: function () {} };
  };
  g.getComputedStyle = function () {
    return { color: 'rgb(0, 0, 0)', getPropertyValue: function () { return ''; } };
  };

  // ---- canvas 2D context: real gradient shape + cumulative geometry work -------
  function canvasContextStub() {
    var points = [];
    var ctx = new Proxy({}, {
      get: function (target, prop) {
        if (prop in target) { return target[prop]; }
        return function () {
          work.canvasCalls++;
          var args = Array.prototype.slice.call(arguments);
          if (prop === 'beginPath') { work.polylines++; points = []; }
          if (prop === 'moveTo') { points.push(args); }
          if (prop === 'lineTo') { work.segments++; points.push(args); }
          if (prop === 'stroke') {
            work.strokes++;
            g.__lxCanvasStrokes.push({ points: points.slice(), alpha: target.globalAlpha,
              width: target.lineWidth, blur: target.shadowBlur || 0 });
          }
          if (prop === 'clearRect') { work.clears++; }
          if (prop === 'arc') { work.arcs++; }
          if (prop === 'fill') { work.fills++; }
          if (prop === 'createRadialGradient') {
            work.gradients++;
            var gradient = { args: args, stops: [], addColorStop: function (offset, color) {
              if (!Number.isFinite(offset) || offset < 0 || offset > 1 || typeof color !== 'string') {
                throw new Error('invalid canvas color stop');
              }
              work.colorStops++;
              gradient.stops.push([offset, color]);
            } };
            g.__lxGradients.push(gradient);
            return gradient;
          }
          return 0;
        };
      },
      set: function (target, prop, value) { target[prop] = value; return true; }
    });
    return ctx;
  }

  // ---- style bag: plain properties + CSS-var store + cssText ------------------
  function styleStub() {
    var vars = {};
    return new Proxy({
      getPropertyValue: function (k) { return vars[k] || ''; },
      setProperty: function (k, v) { work.styleWrites++; vars[k] = v; },
      removeProperty: function (k) { delete vars[k]; },
      cssText: ''
    }, { set: function (target, prop, value) {
      work.styleWrites++;
      if (prop === 'filter' && typeof value === 'string') { g.__lxFilterWrites.push(value); }
      if (prop === 'animation' && value && value !== 'none') { work.animations++; }
      target[prop] = value;
      return true;
    } });
  }

  // ---- created elements + body child tracking ----------------------------------
  var bodyChildren = [];
  g.__lxBodyChildren = function () { return bodyChildren.length; };

  function makeCreatedEl(tag) {
    work.elements++;
    if (String(tag).toLowerCase() === 'canvas') { work.canvases++; }
    var el = {
      tagName: String(tag || 'div').toUpperCase(),
      style: styleStub(),
      classList: classListStub(),
      __attrs: {},
      getAttribute: function (n) { return this.__attrs.hasOwnProperty(n) ? this.__attrs[n] : null; },
      setAttribute: function (n, v) { this.__attrs[n] = String(v); },
      appendChild: function () {},
      addEventListener: function () { work.listenerAdds++; },
      removeEventListener: function () {},
      getBoundingClientRect: function () { return { left: 0, top: 0, width: 100, height: 50 }; },
      getContext: function () { return this.__ctx || (this.__ctx = canvasContextStub()); },
      remove: function () {
        var i = bodyChildren.indexOf(el);
        if (i >= 0) { bodyChildren.splice(i, 1); }
      },
      querySelector: function () { return null; },
      querySelectorAll: function () { return []; }
    };
    g.__lxCreatedElements.push(el);
    return el;
  }

  var documentListeners = {};
  g.__lxFireDocument = function (type) {
    var fns = (documentListeners[type] || []).slice();
    for (var i = 0; i < fns.length; i++) { fns[i]({}); }
  };
  g.document = {
    readyState: 'complete', // init() runs immediately over an empty .lx-math list
    documentElement: { classList: classListStub(), style: styleStub() },
    body: {
      appendChild: function (el) { work.bodyAppends++; bodyChildren.push(el); },
      removeChild: function (el) {
        var i = bodyChildren.indexOf(el);
        if (i >= 0) { bodyChildren.splice(i, 1); }
      }
    },
    querySelectorAll: function () { return []; },
    querySelector: function () { return null; },
    createElement: function (tag) { return makeCreatedEl(tag); },
    createElementNS: function (ns, tag) { return makeCreatedEl(tag); },
    addEventListener: function (type, fn) {
      work.listenerAdds++;
      (documentListeners[type] = documentListeners[type] || []).push(fn);
    },
    removeEventListener: function (type, fn) {
      var fns = documentListeners[type] || [];
      var i = fns.indexOf(fn);
      if (i >= 0) { fns.splice(i, 1); }
    }
  };

  // ---- fake-element factory for the pins ---------------------------------------
  // makePath(attrs): a minimal svg <path> stand-in. __lxMakeEl(attrs, pathCount):
  // an .lx-math container; with pathCount > 0 it carries an inner fake <svg>
  // whose querySelectorAll('path') returns that many fake paths (neonsign,
  // thread, and friends address them).
  function makePath(attrs) {
    var listeners = {};
    return {
      __attrs: attrs || {},
      style: styleStub(),
      getAttribute: function (n) { return this.__attrs.hasOwnProperty(n) ? this.__attrs[n] : null; },
      setAttribute: function (n, v) { this.__attrs[n] = String(v); },
      getBBox: function () { return { x: 0, y: 0, width: 100, height: 50 }; },
      // A non-degenerate screen rect so cancel's strike geometry (getBoundingClientRect
      // per struck glyph) has a real box to strike across in the harness.
      getBoundingClientRect: function () { return { left: 10, top: 10, right: 110, bottom: 60, width: 100, height: 50 }; },
      appendChild: function () {},
      remove: function () {},
      addEventListener: function (type, fn) { work.listenerAdds++; (listeners[type] = listeners[type] || []).push(fn); },
      removeEventListener: function () {},
      __fire: function (type, evt) {
        var fns = (listeners[type] || []).slice();
        for (var i = 0; i < fns.length; i++) { fns[i](evt || {}); }
      }
    };
  }
  g.__lxMakePath = makePath;

  g.__lxMakeEl = function (attrs, pathCount) {
    var listeners = {};
    var paths = [];
    for (var i = 0; i < (pathCount || 0); i++) { paths.push(makePath({})); }
    var svg = pathCount ? {
      querySelectorAll: function (sel) { return sel === 'path' ? paths.slice() : []; },
      addEventListener: function (type, fn) { work.listenerAdds++; (listeners['svg:' + type] = listeners['svg:' + type] || []).push(fn); },
      getBBox: function () { return { x: 0, y: 0, width: 200, height: 80 }; },
      getBoundingClientRect: function () { return { left: 0, top: 0, width: 200, height: 80 }; }
    } : null;
    var el = {
      __attrs: attrs || {},
      __paths: paths,
      style: styleStub(),
      classList: classListStub(),
      getAttribute: function (n) { return this.__attrs.hasOwnProperty(n) ? this.__attrs[n] : null; },
      setAttribute: function (n, v) { this.__attrs[n] = String(v); },
      getBBox: function () { return { x: 0, y: 0, width: 100, height: 50 }; },
      getBoundingClientRect: function () { return { left: 10, top: 10, width: 120, height: 40 }; },
      appendChild: function () {},
      addEventListener: function (type, fn) { work.listenerAdds++; (listeners[type] = listeners[type] || []).push(fn); },
      removeEventListener: function () {},
      __fire: function (type, evt) {
        var fns = (listeners[type] || []).slice();
        for (var i = 0; i < fns.length; i++) { fns[i](evt || {}); }
      },
      querySelector: function (sel) { return sel === 'svg' ? svg : null; },
      querySelectorAll: function (sel) {
        return (sel === 'svg path' || sel === 'path') ? paths.slice() : [];
      }
    };
    return el;
  };
})(globalThis);
