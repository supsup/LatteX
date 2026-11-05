package com.lattex.fx;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.graalvm.polyglot.Value;
import org.graalvm.polyglot.proxy.ProxyExecutable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Real runtime in GraalJS; an explicit DOM/event model, not native browser or clipboard proof. */
class FxCopySourceTest {
    private Context context;
    private Value fx;

    @BeforeEach
    void load() throws IOException {
        context = Context.newBuilder("js").build();
        context.getBindings("js").putMember("__base64Decode", (ProxyExecutable) args ->
            new String(Base64.getDecoder().decode(args[0].asString()), StandardCharsets.ISO_8859_1));
        context.getBindings("js").putMember("__base64Encode", (ProxyExecutable) args ->
            Base64.getEncoder().encodeToString(args[0].asString().getBytes(StandardCharsets.ISO_8859_1)));
        context.getBindings("js").putMember("__utf8Decode", (ProxyExecutable) args -> {
            Value input = args[0];
            byte[] bytes = new byte[(int) input.getArraySize()];
            for (int i = 0; i < bytes.length; i++) {
                bytes[i] = (byte) input.getArrayElement(i).asInt();
            }
            boolean fatal = args[1].asBoolean();
            boolean ignoreBom = args[2].asBoolean();
            try {
                String text = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(fatal ? CodingErrorAction.REPORT : CodingErrorAction.REPLACE)
                    .onUnmappableCharacter(fatal ? CodingErrorAction.REPORT : CodingErrorAction.REPLACE)
                    .decode(ByteBuffer.wrap(bytes)).toString();
                return !ignoreBom && text.startsWith("\uFEFF") ? text.substring(1) : text;
            } catch (CharacterCodingException malformed) {
                throw new IllegalArgumentException("invalid UTF-8");
            }
        });
        context.eval(resource("/com/lattex/fx/harness-dom-stub.js"));
        js("""
            __lxUseInteractiveDom();
            globalThis.atob = function (s) { return __base64Decode(s); };
            globalThis.btoa = function (s) { return __base64Encode(s); };
            globalThis.TextDecoder = function (label, options) {
              this.fatal = !!options.fatal; this.ignoreBOM = !!options.ignoreBOM;
            };
            TextDecoder.prototype.decode = function (bytes) {
              return __utf8Decode(bytes, this.fatal, this.ignoreBOM);
            };
            window.__lxTestHook = function (value) { globalThis.fx = value; };
            window.isSecureContext = true;
            """);
        context.eval(resource("/com/lattex/fx/lattex-fx.js"));
        fx = context.getBindings("js").getMember("fx");
    }

    @AfterEach
    void close() {
        if (context != null) { context.close(); }
    }

    @Test
    void decoderPreservesOriginalBomLineEndingsAndAstralUnits() {
        for (String source : List.of("", "\uFEFF", "\uFEFFx", "x\uFEFFy",
                "A\rB", "A\r\nB", "A\nB", "&#13;\"<>&", "\uD83D\uDE00")) {
            Value decoded = fx.getMember("decodeCopySource").execute(carrier(source));
            assertEquals("ok", decoded.getMember("state").asString());
            assertEquals(source, decoded.getMember("source").asString());
        }
    }

    @Test
    void decoderRefusesMalformedCanonicalUtf8DomainAndUnitLimits() {
        for (String raw : List.of("eA==", "eB", "A", "+w", "/w", "e A", "_w",
                "wK8", "7aCA", "AA", "A".repeat(16385), carrier("x".repeat(4097)))) {
            assertEquals("invalid", fx.getMember("decodeCopySource").execute(raw).getMember("state").asString(), raw);
        }
        assertEquals("ok", fx.getMember("decodeCopySource").execute(carrier("\u0800".repeat(4096)))
            .getMember("state").asString());
        assertEquals("ok", fx.getMember("decodeCopySource").execute(carrier("\uD83D\uDE00".repeat(2048)))
            .getMember("state").asString());
    }

    @Test
    void rawCapRunsBeforeAnyBase64DecoderAndAbsentIsDifferentFromEmpty() {
        js("globalThis.decodeCalls = 0; window.atob = function (s) { decodeCalls++; return __base64Decode(s); };");
        // Legal base64 length, but over cap: the independent mod-4 gate cannot
        // accidentally make a removed cap check look effective.
        fx.getMember("decodeCopySource").execute("A".repeat(16388));
        assertEquals(0, js("decodeCalls").asInt());
        assertEquals("ok", fx.getMember("decodeCopySource").execute("").getMember("state").asString());
        assertEquals("invalid", js("fx.decodeCopySource(null).state").asString());
    }

    @Test
    void onlyExactMathWrapperShapesReceiveTheControl() {
        context.getBindings("js").putMember("payload", carrier("x"));
        for (String shape : List.of("'p', {'class':'lx-math'}",
                "'div', {'class':'lx-math'}", "'span', {'class':'lx-math foreign'}")) {
            assertFalse(js("var wrong = __lxInteractiveEl(" + shape + ");"
                + "wrong.setAttribute('data-lx-source', payload); fx.initCopy(wrong);").asBoolean());
        }
        createWidget("x");
        assertEquals(3, js("el.__children.length").asInt());
        assertFalse(js("fx.initCopy(el)").asBoolean(), "a second init cannot duplicate controls");
    }

    @Test
    void keyboardFocusEscapeAndOutsidePointerHaveExplicitBehavior() {
        createWidget("x");
        js("__lxDispatch(chip, 'keydown', {key:'Enter'});");
        assertFalse(js("menu.hidden").asBoolean());
        assertTrue(js("document.activeElement === item").asBoolean());
        assertEquals("true", js("chip.getAttribute('aria-expanded')").asString());
        for (String key : List.of("ArrowUp", "ArrowDown", "Home", "End")) {
            context.getBindings("js").putMember("key", key);
            assertTrue(js("__lxDispatch(item, 'keydown', {key:key}).defaultPrevented").asBoolean());
            assertTrue(js("document.activeElement === item").asBoolean());
        }
        js("__lxDispatch(item, 'keydown', {key:'Escape'});");
        assertTrue(js("menu.hidden && document.activeElement === chip").asBoolean());
        assertEquals(0, js("__lxDocumentListeners('pointerdown')").asInt());
        js("__lxDispatch(chip, 'click'); __lxDispatch(document.body, 'pointerdown');");
        assertTrue(js("menu.hidden").asBoolean());
    }

    @Test
    void tabClosesWithoutPreventingNativeNavigation() {
        createWidget("x");
        js("__lxDispatch(chip, 'click');");
        assertFalse(js("__lxDispatch(item, 'keydown', {key:'Tab'}).defaultPrevented").asBoolean());
        assertTrue(js("menu.hidden").asBoolean());
        // Actual browser Tab destination is deliberately not modeled here.
    }

    @Test
    void controlEventsDoNotBubbleIntoWrapperEffectsAndBodyClickStillWorks() {
        createWidget("x");
        js("""
            el.setAttribute('data-lx-fx-click', 'pulse');
            fx.init();
            globalThis.wrapperEvents = 0;
            ['pointerdown','pointerup','mousedown','mouseup','touchstart','touchend',
             'click','dblclick','keydown','keyup'].forEach(function(type) {
               el.addEventListener(type, function () { wrapperEvents++; });
               __lxDispatch(chip, type, {key:'Unidentified'});
             });
            """);
        assertEquals(0, js("wrapperEvents").asInt());
        assertTrue(js("el.style.animation === undefined").asBoolean());
        js("__lxDispatch(el, 'click');");
        assertEquals(1, js("wrapperEvents").asInt());
        assertTrue(js("el.style.animation.indexOf('lx-pulse') === 0").asBoolean());
    }

    @Test
    void copiedIsReportedOnlyAfterPromiseResolutionAndSourceIsExact() {
        String source = "\uFEFFx\r\n+ '&#13;'";
        createWidget(source);
        js("__lxDispatch(chip, 'click'); __lxDispatch(item, 'click');");
        assertEquals(source, js("writes[0]").asString());
        assertEquals("Copying\u2026", js("status.textContent").asString());
        assertTrue(js("document.activeElement === chip && menu.hidden").asBoolean());
        js("resolveCopy();");
        assertEquals("Copied", js("status.textContent").asString());
        assertEquals("polite", js("status.getAttribute('aria-live')").asString());
    }

    @Test
    void rejectionMissingApiAndChangedInvalidPayloadNeverReportCopied() {
        createWidget("x");
        js("__lxDispatch(chip, 'click'); __lxDispatch(item, 'click'); rejectCopy();");
        assertEquals("Copy failed. Try again.", js("status.textContent").asString());
        js("delete window.navigator.clipboard; __lxDispatch(chip, 'click'); __lxDispatch(item, 'click');");
        assertTrue(js("status.textContent.indexOf('unavailable') >= 0").asBoolean());
        js("el.setAttribute('data-lx-source', 'eB'); __lxDispatch(chip, 'click'); __lxDispatch(item, 'click');");
        assertEquals(1, js("writes.length").asInt(), "revalidate at action rather than copy a stale cached source");
        assertTrue(js("status.textContent.indexOf('unavailable') >= 0").asBoolean());
    }

    @Test
    void unavailableDecoderStillOffersAccurateAccessibleFeedback() {
        js("delete window.TextDecoder;");
        createWidget("x");
        js("__lxDispatch(chip, 'click'); __lxDispatch(item, 'click');");
        assertEquals(0, js("writes.length").asInt());
        assertTrue(js("status.textContent.indexOf('unavailable') >= 0").asBoolean());
    }

    @Test
    void insecureContextDoesNotAttemptAClipboardWriteEvenWithAHandcraftedApi() {
        createWidget("x");
        js("window.isSecureContext = false; __lxDispatch(chip, 'click'); __lxDispatch(item, 'click');");
        assertEquals(0, js("writes.length").asInt());
        assertTrue(js("status.textContent.indexOf('unavailable') >= 0").asBoolean());
    }

    @Test
    void destroyClosesListenersAndFencesAnOldPromise() {
        createWidget("x");
        js("__lxDispatch(chip, 'click'); __lxDispatch(item, 'click'); __lxDispatch(chip, 'click');");
        assertEquals(1, js("__lxDocumentListeners('pointerdown')").asInt());
        assertTrue(js("window.LatteXFx.destroy(el)").asBoolean());
        assertEquals(0, js("__lxDocumentListeners('pointerdown') + __lxDocumentListeners('focusin')").asInt());
        assertEquals(0, js("el.__children.length").asInt());
        js("resolveCopy();");
        assertEquals("Copying\u2026", js("status.textContent").asString());
        assertFalse(js("window.LatteXFx.destroy(el)").asBoolean());
    }

    private void createWidget(String source) {
        context.getBindings("js").putMember("payload", carrier(source));
        js("""
            globalThis.el = __lxInteractiveEl('span', {'class':'lx-math', 'data-lx-source':payload});
            document.body.appendChild(el);
            globalThis.writes = [];
            window.navigator = {clipboard:{writeText:function (text) {
              writes.push(text);
              return new Promise(function (resolve, reject) {
                globalThis.resolveCopy = resolve; globalThis.rejectCopy = reject;
              });
            }}};
            fx.initCopy(el);
            globalThis.chip = el.querySelector('.lx-chip');
            globalThis.menu = el.querySelector('.lx-copy-menu');
            globalThis.item = el.querySelector('.lx-copy-action');
            globalThis.status = el.querySelector('.lx-copy-status');
            """);
    }

    private static String carrier(String source) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(source.getBytes(StandardCharsets.UTF_8));
    }

    private Value js(String source) {
        return context.eval(Source.create("js", source));
    }

    private static Source resource(String name) throws IOException {
        try (var input = FxCopySourceTest.class.getResourceAsStream(name)) {
            if (input == null) { throw new IOException("Missing resource " + name); }
            return Source.newBuilder("js", new String(input.readAllBytes(), StandardCharsets.UTF_8), name).buildLiteral();
        }
    }
}
