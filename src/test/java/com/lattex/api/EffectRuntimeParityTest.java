package com.lattex.api;

import com.lattex.parse.Effect;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Drift-guard: the {@link Effect} enum and the bundled fx runtime must agree, in BOTH
/// directions — the agreement is a test that fails on drift, never a comment.
///
/// - Every enum token must be HANDLED by the runtime: either a `play()` JS-routine
///   dispatch line (`name === '<token>'`) or a CSS-keyframe `VOCAB` key (`<token>: 1`).
///   A new enum entry without a runtime handler would otherwise parse fine and then
///   silently no-op on the page (the fx-enum-silent-strip class).
/// - Every runtime-handled name must be a valid enum token. A runtime effect the enum
///   doesn't pin is unreachable by authors and rots invisibly.
///
/// The keyframe half additionally requires an `@keyframes lx-<token>` block in the
/// bundled CSS — a VOCAB key without keyframes animates nothing.
class EffectRuntimeParityTest {

    private static final Pattern DISPATCH = Pattern.compile("name === '([a-z]+)'");
    private static final Pattern VOCAB_BLOCK = Pattern.compile("var VOCAB = \\{([^}]*)}");
    private static final Pattern VOCAB_KEY = Pattern.compile("([a-z]+): 1");

    private static Set<String> runtimeHandled(String js) {
        Set<String> handled = new HashSet<>();
        Matcher dispatch = DISPATCH.matcher(js);
        while (dispatch.find()) {
            handled.add(dispatch.group(1));
        }
        Matcher block = VOCAB_BLOCK.matcher(js);
        assertTrue(block.find(), "fx runtime must declare the keyframe VOCAB map");
        Matcher key = VOCAB_KEY.matcher(block.group(1));
        while (key.find()) {
            handled.add(key.group(1));
        }
        return handled;
    }

    @Test
    void everyEffectTokenIsHandledByTheRuntime() {
        String js = LatteX.fxRuntimeJs();
        Set<String> handled = runtimeHandled(js);
        for (Effect effect : Effect.values()) {
            assertTrue(handled.contains(effect.token()),
                "Effect." + effect + " has no fx-runtime handler (no play() dispatch line"
                    + " and no VOCAB key) — it would silently no-op on the page");
        }
    }

    @Test
    void everyRuntimeHandledNameIsAnEffectToken() {
        Set<String> tokens = new HashSet<>();
        for (Effect effect : Effect.values()) {
            tokens.add(effect.token());
        }
        for (String name : runtimeHandled(LatteX.fxRuntimeJs())) {
            assertTrue(tokens.contains(name),
                "fx runtime handles '" + name + "' but Effect has no such token — "
                    + "authors can never reach it");
        }
    }

    @Test
    void everyVocabKeyframeEffectHasItsCssKeyframes() {
        String js = LatteX.fxRuntimeJs();
        String css = LatteX.fxStylesCss();
        Matcher block = VOCAB_BLOCK.matcher(js);
        assertTrue(block.find());
        Matcher key = VOCAB_KEY.matcher(block.group(1));
        int checked = 0;
        while (key.find()) {
            String token = key.group(1);
            if (token.equals("none")) {
                continue;
            }
            assertTrue(css.contains("@keyframes lx-" + token),
                "VOCAB effect '" + token + "' has no @keyframes lx-" + token
                    + " in lattex-fx.css — it would animate nothing");
            checked++;
        }
        assertTrue(checked >= 5, "expected at least boom/pulse/fade/glow/glitch in VOCAB");
        // Sanity: token casing convention holds (enum name lowercased == token).
        assertEquals("shatter", Effect.SHATTER.token().toLowerCase(Locale.ROOT));
    }

    @Test
    void everyEffectKeyframeInTheCssBelongsToTheVocabPath() {
        // The reverse CSS direction (plan 32148cc8 S5): an `@keyframes lx-<token>`
        // whose token is an Effect NOT in VOCAB is dead CSS shadowing a JS-routine
        // effect (play() returns before the keyframe path) — flag it. Non-token
        // lx-* keyframes (e.g. lx-holo-scan) are internal helpers and stay allowed.
        Set<String> vocab = new HashSet<>();
        Matcher block = VOCAB_BLOCK.matcher(LatteX.fxRuntimeJs());
        assertTrue(block.find());
        Matcher key = VOCAB_KEY.matcher(block.group(1));
        while (key.find()) {
            vocab.add(key.group(1));
        }
        Set<String> tokens = new HashSet<>();
        for (Effect effect : Effect.values()) {
            tokens.add(effect.token());
        }
        Matcher keyframes = Pattern.compile("@keyframes lx-([a-z-]+)")
            .matcher(LatteX.fxStylesCss());
        int seen = 0;
        while (keyframes.find()) {
            seen++;
            String name = keyframes.group(1);
            if (tokens.contains(name)) {
                assertTrue(vocab.contains(name),
                    "lattex-fx.css has @keyframes lx-" + name + " but '" + name
                        + "' is a JS-routine effect, not a VOCAB keyframe effect — "
                        + "that keyframe can never play (dead CSS, or a mis-wired effect)");
            }
        }
        assertTrue(seen >= 5, "expected the keyframe blocks to be scanned, saw " + seen);
    }
}
