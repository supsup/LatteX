/* Trusted host runtime for InteractiveMath whole-expression FLIP/crossfade. */
(function (global) {
  'use strict';

  var instances = new WeakMap();
  var documentStates = new WeakMap();

  function reducedMotion() {
    return !!(global.matchMedia
      && global.matchMedia('(prefers-reduced-motion: reduce)').matches);
  }

  function durationOf(root) {
    var value = Number(root.getAttribute('data-lx-duration'));
    return Number.isFinite(value) && value >= 0 && value <= 2000 ? value : 240;
  }

  function setHidden(element, hidden) {
    element.setAttribute('aria-hidden', hidden ? 'true' : 'false');
    element.inert = !!hidden;
  }

  function documentState(doc) {
    var state = documentStates.get(doc);
    if (state) { return state; }
    state = { controllers: new Set(), observer: null };
    var Observer = (doc.defaultView && doc.defaultView.MutationObserver)
      || global.MutationObserver;
    if (Observer && doc.documentElement) {
      state.observer = new Observer(function () {
        Array.from(state.controllers).forEach(function (controller) {
          if (!controller.root.isConnected
              || controller.root.ownerDocument !== controller.trackedDocument) {
            controller.destroy();
          }
        });
      });
      state.observer.observe(doc.documentElement, { childList: true, subtree: true });
    }
    documentStates.set(doc, state);
    return state;
  }

  function untrack(controller) {
    var doc = controller.trackedDocument;
    var state = controller.trackedState;
    if (!state) { return; }
    state.controllers.delete(controller);
    if (state.controllers.size === 0) {
      if (state.observer) { state.observer.disconnect(); }
      documentStates.delete(doc);
    }
  }

  function animateFlip(outgoing, incoming, duration, animations) {
    if (!incoming.animate || !outgoing.animate || reducedMotion() || duration === 0) {
      return;
    }
    var started = [];
    try {
      var outgoingVisual = outgoing.querySelector ? outgoing.querySelector('svg') : null;
      var incomingVisual = incoming.querySelector ? incoming.querySelector('svg') : null;
      var first = (outgoingVisual || outgoing).getBoundingClientRect();
      var last = (incomingVisual || incoming).getBoundingClientRect();
      var scaleX = first.width > 0 && last.width > 0 ? first.width / last.width : 1;
      var scaleY = first.height > 0 && last.height > 0 ? first.height / last.height : 1;
      var deltaX = first.left - last.left;
      var deltaY = first.top - last.top;
      var start = 'translate(' + deltaX + 'px, ' + deltaY + 'px) scale('
        + scaleX + ', ' + scaleY + ')';
      var timing = { duration: duration, easing: 'ease-in-out', fill: 'none' };
      started.push(incoming.animate([
        { opacity: 0, transform: start },
        { opacity: 1, transform: 'none' }
      ], timing));
      started.push(outgoing.animate([
        { opacity: 1, transform: 'none' },
        { opacity: 0, transform: 'none' }
      ], timing));
      started.forEach(function (animation) { animations.push(animation); });
    } catch (ignored) {
      started.forEach(function (animation) {
        if (animation && animation.cancel) { animation.cancel(); }
      });
    }
  }

  function initOne(root) {
    var existing = instances.get(root);
    if (existing) { return existing; }
    var from = root.querySelector('[data-lx-state="from"]');
    var to = root.querySelector('[data-lx-state="to"]');
    var stage = root.querySelector('.lx-transition__stage');
    var control = root.querySelector('.lx-transition__control');
    if (!from || !to || !stage || !control || !root.ownerDocument) { return null; }
    var trackedDocument = root.ownerDocument;
    var trackedState = documentState(trackedDocument);

    var shown = false;
    var pinned = false;
    var hovering = false;
    var destroyed = false;
    var animations = [];

    function cancelAnimations() {
      animations.forEach(function (animation) {
        if (animation && animation.cancel) { animation.cancel(); }
      });
      animations = [];
    }

    function updateControl() {
      control.setAttribute('aria-expanded', shown ? 'true' : 'false');
      if (hovering && !pinned && shown) {
        control.textContent = 'Keep alternate equation';
      } else {
        control.textContent = shown
          ? 'Show initial equation' : 'Show alternate equation';
      }
    }

    function showAlternate(next, animate, force) {
      if (destroyed) { return; }
      if (!root.isConnected) {
        controller.destroy();
        return;
      }
      next = !!next;
      if (!force && next === shown) {
        updateControl();
        return;
      }
      var outgoing = next ? from : to;
      var incoming = next ? to : from;
      cancelAnimations();
      shown = next;
      root.classList.toggle('lx-transition--to', shown);
      updateControl();
      setHidden(from, shown);
      setHidden(to, !shown);
      if (animate) { animateFlip(outgoing, incoming, durationOf(root), animations); }
    }

    function togglePinned() {
      if (hovering && !pinned) {
        pinned = true;
        hovering = false;
        showAlternate(true, false, false);
        return;
      }
      hovering = false;
      pinned = !shown;
      showAlternate(pinned, true, false);
    }

    function onClick() { togglePinned(); }
    function onKeyDown(event) {
      if (event.key === 'Enter' || event.key === ' ') {
        event.preventDefault();
        togglePinned();
      }
    }
    function onFocus() {
      if (!hovering) { return; }
      hovering = false;
      showAlternate(pinned, true, false);
    }
    function onEnter() {
      hovering = true;
      showAlternate(true, true, false);
    }
    function onLeave() {
      hovering = false;
      showAlternate(pinned, true, false);
    }

    var controller = {
      root: root,
      trackedDocument: trackedDocument,
      trackedState: trackedState,
      showAlternate: function (value) { showAlternate(value, true, false); },
      destroy: function () {
        if (destroyed) { return; }
        destroyed = true;
        cancelAnimations();
        control.removeEventListener('click', onClick);
        control.removeEventListener('keydown', onKeyDown);
        control.removeEventListener('focus', onFocus);
        stage.removeEventListener('mouseenter', onEnter);
        stage.removeEventListener('mouseleave', onLeave);
        root.classList.remove('lx-transition--ready');
        root.classList.remove('lx-transition--to');
        control.setAttribute('aria-expanded', 'false');
        control.textContent = 'Show alternate equation';
        from.removeAttribute('aria-hidden');
        to.removeAttribute('aria-hidden');
        from.inert = false;
        to.inert = false;
        instances.delete(root);
        untrack(controller);
      }
    };

    instances.set(root, controller);
    trackedState.controllers.add(controller);
    control.addEventListener('click', onClick);
    control.addEventListener('keydown', onKeyDown);
    control.addEventListener('focus', onFocus);
    stage.addEventListener('mouseenter', onEnter);
    stage.addEventListener('mouseleave', onLeave);
    root.classList.add('lx-transition--ready');
    showAlternate(false, false, true);
    return controller;
  }

  function init(scope) {
    var rootScope = scope || global.document;
    if (!rootScope) { return []; }
    var roots = [];
    if (rootScope.matches && rootScope.matches('[data-lx-transition="true"]')) {
      roots.push(rootScope);
    }
    if (rootScope.querySelectorAll) {
      Array.from(rootScope.querySelectorAll('[data-lx-transition="true"]'))
        .forEach(function (root) {
          if (roots.indexOf(root) < 0) { roots.push(root); }
        });
    }
    return roots.map(initOne).filter(function (value) { return !!value; });
  }

  function destroy(scope) {
    var rootScope = scope || global.document;
    if (!rootScope) { return; }
    var roots = [];
    if (rootScope.matches && rootScope.matches('[data-lx-transition="true"]')) {
      roots.push(rootScope);
    }
    if (rootScope.querySelectorAll) {
      Array.from(rootScope.querySelectorAll('[data-lx-transition="true"]'))
        .forEach(function (root) {
          if (roots.indexOf(root) < 0) { roots.push(root); }
        });
    }
    roots.forEach(function (root) {
      var controller = instances.get(root);
      if (controller) { controller.destroy(); }
    });
  }

  var api = Object.freeze({ init: init, destroy: destroy });
  global.LatteXInteractive = api;
  if (typeof global.__lxInteractiveTestHook === 'function') {
    global.__lxInteractiveTestHook(api);
  }

  if (global.document) {
    if (global.document.readyState === 'loading') {
      global.document.addEventListener('DOMContentLoaded', function () {
        init(global.document);
      }, { once: true });
    } else if (global.queueMicrotask) {
      global.queueMicrotask(function () { init(global.document); });
    } else {
      Promise.resolve().then(function () { init(global.document); });
    }
  }
})(globalThis);
