/* Stub DOM for the fx-runtime JS harness (plan e09b28be).
 *
 * Just enough browser surface for lattex-fx.js to LOAD and expose its internals
 * through window.__lxTestHook — deliberately NOT a jsdom: the harness pins unit
 * behavior of extracted internals (placement compose, glyphmap grammar), not
 * whole-page integration (BrewShot owns real-browser eyeballing).
 *
 * Load this BEFORE lattex-fx.js in the same GraalJS context. The Java side
 * installs window.__lxTestHook to capture the internals object.
 */
(function (g) {
  'use strict';

  function classListStub() {
    var set = {};
    return {
      add: function (c) { set[c] = true; },
      remove: function (c) { delete set[c]; },
      contains: function (c) { return !!set[c]; }
    };
  }

  // requestAnimationFrame: queue callbacks; tests flush deterministically via
  // __lxFlushRaf(maxFrames) instead of a real clock.
  var rafQueue = [];
  g.requestAnimationFrame = function (cb) { rafQueue.push(cb); return rafQueue.length; };
  g.cancelAnimationFrame = function () {};
  g.__lxFlushRaf = function (maxFrames) {
    var frames = 0;
    while (rafQueue.length && frames < maxFrames) {
      var batch = rafQueue;
      rafQueue = [];
      for (var i = 0; i < batch.length; i++) { batch[i](frames * 16); }
      frames++;
    }
    return frames;
  };

  g.window = g;
  g.matchMedia = function () { return { matches: false, addEventListener: function () {} }; };
  g.addEventListener = function () {};
  g.removeEventListener = function () {};
  g.getComputedStyle = function () {
    return { color: 'rgb(0, 0, 0)', getPropertyValue: function () { return ''; } };
  };
  g.setInterval = function () { return 0; };
  g.clearInterval = function () {};
  g.setTimeout = function (cb) { return 0; };
  g.clearTimeout = function () {};

  g.document = {
    readyState: 'complete', // init() runs immediately over an empty .lx-math list
    documentElement: { classList: classListStub(), style: {} },
    body: { appendChild: function () {}, removeChild: function () {} },
    querySelectorAll: function () { return []; },
    querySelector: function () { return null; },
    createElement: function () {
      return {
        style: {}, classList: classListStub(),
        setAttribute: function () {}, getAttribute: function () { return null; },
        appendChild: function () {}, addEventListener: function () {}
      };
    },
    createElementNS: function () { return this.createElement(); },
    addEventListener: function () {},
    removeEventListener: function () {}
  };

  // Fake-element factory for the pins: a minimal path/element whose attribute map
  // and style object the tests inspect directly. style doubles as a plain
  // property bag (style.transform = ...) and a CSS-var store (getPropertyValue).
  g.__lxMakeEl = function (attrs) {
    var listeners = {};
    var styleVars = {};
    return {
      __attrs: attrs || {},
      style: {
        getPropertyValue: function (k) { return styleVars[k] || ''; },
        setProperty: function (k, v) { styleVars[k] = v; }
      },
      getAttribute: function (name) {
        return this.__attrs.hasOwnProperty(name) ? this.__attrs[name] : null;
      },
      setAttribute: function (name, v) { this.__attrs[name] = String(v); },
      getBBox: function () { return { x: 0, y: 0, width: 100, height: 50 }; },
      addEventListener: function (type, fn) {
        (listeners[type] = listeners[type] || []).push(fn);
      },
      __fire: function (type, evt) {
        var fns = listeners[type] || [];
        for (var i = 0; i < fns.length; i++) { fns[i](evt || {}); }
      },
      querySelector: function () { return null; },
      querySelectorAll: function () { return []; }
    };
  };
})(globalThis);
