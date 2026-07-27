package com.lattex.harness;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.brewshot.BrewShot;
import com.brewshot.MiniJson;
import com.lattex.api.InteractiveMath;
import com.lattex.api.InteractiveResult;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Real-browser contract for the separate trusted equation-transition runtime. */
class InteractiveMathBrowserTest {
    @TempDir
    Path fixtures;

    private Path livePage;
    private Path noJsPage;

    @BeforeEach
    void writeCurrentSourceFixtures() throws Exception {
        InteractiveResult result = InteractiveMath.render(
            "\\frac{x^2 + 1}{y}", "\\sqrt{x + y}");
        assertEquals(InteractiveResult.Status.INTERACTIVE, result.status());

        Files.writeString(fixtures.resolve("lattex-interactive.css"),
            InteractiveMath.stylesCss(), StandardCharsets.UTF_8);
        Files.writeString(fixtures.resolve("lattex-interactive.js"),
            InteractiveMath.runtimeJs(), StandardCharsets.UTF_8);
        livePage = fixtures.resolve("interactive.html");
        noJsPage = fixtures.resolve("interactive-no-js.html");
        Files.writeString(livePage, page(result.html(), true), StandardCharsets.UTF_8);
        Files.writeString(noJsPage, page(result.html(), false), StandardCharsets.UTF_8);
    }

    @Test
    void liveRuntimeFlipsReversesKeepsFocusAndTearsDownWhenDetached() throws Exception {
        BrowserGate.browserPin();
        try (BrewShot chrome = BrewShot.launch(900, 700)) {
            chrome.captureConsole(true);
            chrome.open(livePage.toUri().toString());
            chrome.waitReady();
            chrome.settle(120);

            Object initial = chrome.eval(stateScript());
            assertEquals(true, MiniJson.get(initial, "ready"));
            assertEquals(false, MiniJson.get(initial, "toClass"));
            assertEquals("false", MiniJson.get(initial, "expanded"));
            assertEquals("false", MiniJson.get(initial, "fromHidden"));
            assertEquals("true", MiniJson.get(initial, "toHidden"));
            assertEquals(2.0, MiniJson.get(initial, "svgs"));

            Object idempotent = chrome.eval("""
                (function () {
                  var root = document.querySelector('[data-lx-transition="true"]');
                  var first = LatteXInteractive.init(root)[0];
                  var second = LatteXInteractive.init(root)[0];
                  return first === second;
                })()
                """);
            assertEquals(true, idempotent, "real-browser init must be idempotent");

            Object enter = chrome.eval("""
                (function () {
                  var root = document.querySelector('[data-lx-transition="true"]');
                  var button = root.querySelector('.lx-transition__control');
                  button.focus();
                  button.dispatchEvent(new KeyboardEvent('keydown', {
                    key: 'Enter', bubbles: true, cancelable: true
                  }));
                  var from = root.querySelector('[data-lx-state="from"]');
                  var to = root.querySelector('[data-lx-state="to"]');
                  return {
                    focused: document.activeElement === button,
                    animations: from.getAnimations().length + to.getAnimations().length,
                    expanded: button.getAttribute('aria-expanded')
                  };
                })()
                """);
            assertEquals(true, MiniJson.get(enter, "focused"));
            assertTrue(((Double) MiniJson.get(enter, "animations")) >= 2,
                "whole-expression FLIP/crossfade must create live endpoint animations");
            assertEquals("true", MiniJson.get(enter, "expanded"));
            chrome.settle(300);
            assertToState(chrome.eval(stateScript()));

            Object space = chrome.eval("""
                (function () {
                  var button = document.querySelector('.lx-transition__control');
                  button.dispatchEvent(new KeyboardEvent('keydown', {
                    key: ' ', bubbles: true, cancelable: true
                  }));
                  return document.activeElement === button;
                })()
                """);
            assertEquals(true, space, "Space reversal must preserve focus on the same control");
            chrome.settle(300);
            Object reversed = chrome.eval(stateScript());
            assertEquals(false, MiniJson.get(reversed, "toClass"));
            assertEquals("false", MiniJson.get(reversed, "expanded"));

            chrome.eval("""
                (function () {
                  var outside = document.querySelector('#lx-outside');
                  outside.setAttribute('tabindex', '-1');
                  outside.focus();
                  return true;
                })()
                """);
            chrome.hover(".lx-transition__stage");
            chrome.settle(300);
            assertPreviewState(chrome.eval(stateScript()), false);
            chrome.eval("document.querySelector('.lx-transition__control').focus(); true");
            chrome.settle(300);
            Object focusReset = chrome.eval(stateScript());
            assertEquals(false, MiniJson.get(focusReset, "toClass"),
                "control focus must end an ambiguous pointer-only preview");
            assertEquals(true, MiniJson.get(focusReset, "focused"));
            chrome.hover("#lx-outside");
            chrome.hover(".lx-transition__stage");
            chrome.settle(300);
            assertPreviewState(chrome.eval(stateScript()), true);
            chrome.hover("#lx-outside");
            chrome.settle(300);
            assertEquals(false, MiniJson.get(chrome.eval(stateScript()), "toClass"),
                "unpinned hover preview must reverse on pointer exit");

            chrome.click(".lx-transition__control");
            chrome.settle(300);
            assertToState(chrome.eval(stateScript()));
            chrome.hover(".lx-transition__stage");
            chrome.hover("#lx-outside");
            assertToState(chrome.eval(stateScript()));
            chrome.click(".lx-transition__control");
            chrome.settle(300);
            assertEquals(false, MiniJson.get(chrome.eval(stateScript()), "toClass"),
                "a second explicit click must reverse the pinned state");

            chrome.eval("""
                (function () {
                  globalThis.__detachedTransition = document.querySelector(
                    '[data-lx-transition="true"]');
                  globalThis.__detachedTransition.remove();
                  return true;
                })()
                """);
            chrome.settle(100);
            Object detached = chrome.eval("""
                (function () {
                  var root = globalThis.__detachedTransition;
                  return {
                    ready: root.classList.contains('lx-transition--ready'),
                    fromHidden: root.querySelector('[data-lx-state="from"]')
                      .getAttribute('aria-hidden'),
                    toHidden: root.querySelector('[data-lx-state="to"]')
                      .getAttribute('aria-hidden'),
                    animations: root.getAnimations({subtree: true}).length
                  };
                })()
                """);
            assertEquals(false, MiniJson.get(detached, "ready"));
            assertEquals(null, MiniJson.get(detached, "fromHidden"));
            assertEquals(null, MiniJson.get(detached, "toHidden"));
            assertEquals(0.0, MiniJson.get(detached, "animations"));
            assertEquals(java.util.List.of(), chrome.errors());
        }
    }

    @Test
    void reducedMotionSwitchesExactlyWithoutAnimating() throws Exception {
        BrowserGate.browserPin();
        try (BrewShot chrome = BrewShot.launch(900, 700)) {
            chrome.reducedMotion("reduce");
            chrome.captureConsole(true);
            chrome.open(livePage.toUri().toString());
            chrome.waitReady();
            chrome.click(".lx-transition__control");

            Object state = chrome.eval("""
                (function () {
                  var root = document.querySelector('[data-lx-transition="true"]');
                  return {
                    toClass: root.classList.contains('lx-transition--to'),
                    animations: root.getAnimations({subtree: true}).length,
                    expanded: root.querySelector('.lx-transition__control')
                      .getAttribute('aria-expanded')
                  };
                })()
                """);
            assertEquals(true, MiniJson.get(state, "toClass"));
            assertEquals(0.0, MiniJson.get(state, "animations"));
            assertEquals("true", MiniJson.get(state, "expanded"));
            assertEquals(java.util.List.of(), chrome.errors());
        }
    }

    @Test
    void cssWithoutJavascriptLeavesBothLabeledStatesVisible() throws Exception {
        BrowserGate.browserPin();
        try (BrewShot chrome = BrewShot.launch(900, 700)) {
            chrome.open(noJsPage.toUri().toString());
            chrome.waitReady();

            Object state = chrome.eval("""
                (function () {
                  var root = document.querySelector('[data-lx-transition="true"]');
                  var from = root.querySelector('[data-lx-state="from"]');
                  var to = root.querySelector('[data-lx-state="to"]');
                  var button = root.querySelector('.lx-transition__control');
                  return {
                    ready: root.classList.contains('lx-transition--ready'),
                    fromVisible: getComputedStyle(from).display !== 'none'
                      && getComputedStyle(from).opacity !== '0',
                    toVisible: getComputedStyle(to).display !== 'none'
                      && getComputedStyle(to).opacity !== '0',
                    controlDisplay: getComputedStyle(button).display,
                    labels: root.querySelectorAll('.lx-transition__label').length
                  };
                })()
                """);
            assertEquals(false, MiniJson.get(state, "ready"));
            assertEquals(true, MiniJson.get(state, "fromVisible"));
            assertEquals(true, MiniJson.get(state, "toVisible"));
            assertEquals("none", MiniJson.get(state, "controlDisplay"));
            assertEquals(2.0, MiniJson.get(state, "labels"));
        }
    }

    private static void assertToState(Object state) {
        assertEquals(true, MiniJson.get(state, "toClass"));
        assertEquals("true", MiniJson.get(state, "expanded"));
        assertEquals("true", MiniJson.get(state, "fromHidden"));
        assertEquals("false", MiniJson.get(state, "toHidden"));
        assertEquals(true, MiniJson.get(state, "focused"));
        assertEquals("Show initial equation", MiniJson.get(state, "label"));
    }

    private static void assertPreviewState(Object state, boolean focused) {
        assertEquals(true, MiniJson.get(state, "toClass"));
        assertEquals("true", MiniJson.get(state, "expanded"));
        assertEquals("true", MiniJson.get(state, "fromHidden"));
        assertEquals("false", MiniJson.get(state, "toHidden"));
        assertEquals(focused, MiniJson.get(state, "focused"));
        assertEquals("Keep alternate equation", MiniJson.get(state, "label"));
    }

    private static String stateScript() {
        return """
            (function () {
              var root = document.querySelector('[data-lx-transition="true"]');
              var button = root.querySelector('.lx-transition__control');
              return {
                ready: root.classList.contains('lx-transition--ready'),
                toClass: root.classList.contains('lx-transition--to'),
                expanded: button.getAttribute('aria-expanded'),
                fromHidden: root.querySelector('[data-lx-state="from"]')
                  .getAttribute('aria-hidden'),
                toHidden: root.querySelector('[data-lx-state="to"]')
                  .getAttribute('aria-hidden'),
                focused: document.activeElement === button,
                label: button.textContent,
                svgs: root.querySelectorAll('svg').length
              };
            })()
            """;
    }

    private static String page(String component, boolean runtime) {
        return "<!doctype html>\n<html lang=\"en\"><head><meta charset=\"utf-8\">"
            + "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
            + "<link rel=\"stylesheet\" href=\"lattex-interactive.css\"></head><body>"
            + component
            + "<p id=\"lx-outside\">Outside transition</p>"
            + (runtime ? "<script src=\"lattex-interactive.js\"></script>" : "")
            + "</body></html>";
    }
}
