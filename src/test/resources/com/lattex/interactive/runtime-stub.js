(function (g) {
  'use strict';

  var roots = [];
  var observers = [];
  var reduced = false;
  var animations = 0;
  var cancelledAnimations = 0;
  var animationAttempts = 0;
  var throwOnAnimationAttempt = 0;

  function classList() {
    var values = {};
    return {
      add: function (name) { values[name] = true; },
      remove: function (name) { delete values[name]; },
      contains: function (name) { return !!values[name]; },
      toggle: function (name, force) {
        var next = force === undefined ? !values[name] : !!force;
        if (next) { values[name] = true; } else { delete values[name]; }
        return next;
      }
    };
  }

  function eventTarget() {
    var listeners = {};
    return {
      addEventListener: function (type, fn) {
        (listeners[type] = listeners[type] || []).push(fn);
      },
      removeEventListener: function (type, fn) {
        var values = listeners[type] || [];
        var index = values.indexOf(fn);
        if (index >= 0) { values.splice(index, 1); }
      },
      __fire: function (type, event) {
        (listeners[type] || []).slice().forEach(function (fn) { fn(event || {}); });
      },
      __listenerCount: function (type) { return (listeners[type] || []).length; }
    };
  }

  function element(kind, box) {
    var events = eventTarget();
    var attrs = {};
    var value = {
      kind: kind,
      classList: classList(),
      textContent: '',
      inert: false,
      isConnected: true,
      getAttribute: function (name) {
        return Object.prototype.hasOwnProperty.call(attrs, name) ? attrs[name] : null;
      },
      setAttribute: function (name, attrValue) { attrs[name] = String(attrValue); },
      removeAttribute: function (name) { delete attrs[name]; },
      getBoundingClientRect: function () {
        return box || { left: 0, top: 0, width: 100, height: 40 };
      },
      animate: function (frames, options) {
        animationAttempts++;
        if (animationAttempts === throwOnAnimationAttempt) {
          throw new Error('synthetic animation failure');
        }
        animations++;
        value.__lastFrames = frames;
        value.__lastTiming = options;
        return {
          cancel: function () { cancelledAnimations++; }
        };
      },
      addEventListener: events.addEventListener,
      removeEventListener: events.removeEventListener,
      __fire: events.__fire,
      __listenerCount: events.__listenerCount
    };
    return value;
  }

  var doc = {
    readyState: 'complete',
    defaultView: g,
    documentElement: {},
    querySelectorAll: function (selector) {
      return selector === '[data-lx-transition="true"]' ? roots.slice() : [];
    },
    addEventListener: function () {}
  };
  g.document = doc;
  g.queueMicrotask = function (fn) { fn(); };
  g.matchMedia = function () { return { matches: reduced }; };

  g.MutationObserver = function (callback) {
    this.callback = callback;
    this.connected = false;
    this.observe = function () { this.connected = true; observers.push(this); };
    this.disconnect = function () { this.connected = false; };
  };

  g.__makeTransition = function () {
    var root = element('root', { left: 10, top: 20, width: 180, height: 70 });
    var from = element('from', { left: 10, top: 20, width: 180, height: 70 });
    var to = element('to', { left: 30, top: 30, width: 120, height: 50 });
    var stage = element('stage', { left: 10, top: 20, width: 180, height: 70 });
    var control = element('control', { left: 10, top: 100, width: 130, height: 30 });
    root.ownerDocument = doc;
    from.ownerDocument = doc;
    to.ownerDocument = doc;
    stage.ownerDocument = doc;
    control.ownerDocument = doc;
    var fromSvg = element('from-svg', { left: 12, top: 24, width: 170, height: 60 });
    var toSvg = element('to-svg', { left: 32, top: 34, width: 110, height: 40 });
    fromSvg.ownerDocument = doc;
    toSvg.ownerDocument = doc;
    from.querySelector = function (selector) { return selector === 'svg' ? fromSvg : null; };
    to.querySelector = function (selector) { return selector === 'svg' ? toSvg : null; };
    root.setAttribute('data-lx-transition', 'true');
    root.setAttribute('data-lx-duration', '240');
    control.setAttribute('aria-expanded', 'false');
    control.textContent = 'Show alternate equation';
    root.__from = from;
    root.__to = to;
    root.__stage = stage;
    root.__control = control;
    root.matches = function (selector) {
      return selector === '[data-lx-transition="true"]';
    };
    root.querySelector = function (selector) {
      if (selector === '[data-lx-state="from"]') { return from; }
      if (selector === '[data-lx-state="to"]') { return to; }
      if (selector === '.lx-transition__stage') { return stage; }
      if (selector === '.lx-transition__control') { return control; }
      return null;
    };
    root.querySelectorAll = function () { return []; };
    roots.push(root);
    return root;
  };

  g.__setReduced = function (value) { reduced = !!value; };
  g.__setAnimationFailure = function (attempt) { throwOnAnimationAttempt = attempt; };
  g.__animationCount = function () { return animations; };
  g.__cancelledAnimationCount = function () { return cancelledAnimations; };
  g.__observerCount = function () {
    return observers.filter(function (observer) { return observer.connected; }).length;
  };
  g.__mutate = function () {
    observers.slice().forEach(function (observer) {
      if (observer.connected) { observer.callback([]); }
    });
  };
})(globalThis);
