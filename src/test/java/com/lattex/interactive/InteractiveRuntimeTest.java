package com.lattex.interactive;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lattex.api.InteractiveMath;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.graalvm.polyglot.Value;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class InteractiveRuntimeTest {
    private Context context;
    private Value api;

    private void boot(boolean reduced) throws IOException {
        context = Context.newBuilder("js").build();
        context.eval(source("/com/lattex/interactive/runtime-stub.js", "runtime-stub.js"));
        if (reduced) {
            context.eval("js", "__setReduced(true)");
        }
        context.eval("js", "globalThis.__lxInteractiveTestHook = function (value) {"
            + " globalThis.__interactiveApi = value; };");
        context.eval(Source.newBuilder("js", InteractiveMath.runtimeJs(),
            "lattex-interactive.js").buildLiteral());
        api = context.getBindings("js").getMember("__interactiveApi");
        assertNotNull(api);
    }

    @AfterEach
    void close() {
        if (context != null) {
            context.close();
        }
    }

    @Test
    void initializationIsIdempotentAndExposesOnlyTheInitialState() throws IOException {
        boot(false);
        Value root = js("globalThis.__root = __makeTransition(); __root");

        Value first = api.getMember("init").execute(root);
        Value second = api.getMember("init").execute(root);

        assertEquals(1, first.getArraySize());
        assertEquals(1, second.getArraySize());
        assertTrue(root.getMember("classList").getMember("contains")
            .execute("lx-transition--ready").asBoolean());
        assertFalse(root.getMember("classList").getMember("contains")
            .execute("lx-transition--to").asBoolean());
        assertEquals("false", js("__root.__from.getAttribute('aria-hidden')").asString());
        assertEquals("true", js("__root.__to.getAttribute('aria-hidden')").asString());
        assertEquals("false", js("__root.__control.getAttribute('aria-expanded')").asString());
        assertEquals(1, js("__root.__control.__listenerCount('click')").asInt());
        assertEquals(1, js("__root.__control.__listenerCount('keydown')").asInt());
        assertEquals(1, js("__root.__control.__listenerCount('focus')").asInt());
        assertEquals(1, js("__root.__stage.__listenerCount('mouseenter')").asInt());
        assertEquals(1, js("__observerCount()").asInt());
    }

    @Test
    void clickKeyboardAndHoverAreReversibleWithoutMovingFocus() throws IOException {
        boot(false);
        Value root = js("globalThis.__root = __makeTransition(); __root");
        api.getMember("init").execute(root);

        js("__root.__control.__fire('click', {})");
        assertToState();
        assertEquals(2, js("__animationCount()").asInt());
        assertTrue(js("__root.__to.__lastFrames[0].transform"
            + ".indexOf('translate(-20px, -10px)') >= 0").asBoolean(),
            "FLIP geometry must come from the endpoint SVG boxes, not equal-width grid wrappers");
        assertTrue(js("__root.__to.__lastFrames[0].transform"
            + ".indexOf('scale(1.5454545454545454, 1.5)') >= 0").asBoolean());

        js("globalThis.__prevented = false; __root.__control.__fire('keydown', {"
            + " key: 'Enter', preventDefault: function () { globalThis.__prevented = true; } });");
        assertTrue(js("__prevented").asBoolean());
        assertFromState();

        js("__root.__stage.__fire('mouseenter', {})");
        assertPreviewState();
        js("__root.__control.__fire('focus', {})");
        assertFromState();
        js("__root.__stage.__fire('mouseenter', {})");
        assertPreviewState();
        js("__root.__stage.__fire('mouseleave', {})");
        assertFromState();

        js("__root.__stage.__fire('mouseenter', {})");
        assertPreviewState();
        js("__root.__control.__fire('click', {})");
        assertToState();
        js("__root.__stage.__fire('mouseleave', {})");
        assertToState();
        js("__root.__control.__fire('click', {})");
        assertFromState();

        js("__root.__control.__fire('keydown', {"
            + " key: ' ', preventDefault: function () {} });");
        assertToState();
        assertEquals("control", root.getMember("__control").getMember("kind").asString(),
            "the same explicit control remains in place throughout every transition");
    }

    @Test
    void reducedMotionSwitchesStateWithoutAnimation() throws IOException {
        boot(true);
        Value root = js("globalThis.__root = __makeTransition(); __root");
        api.getMember("init").execute(root);

        js("__root.__control.__fire('click', {})");

        assertToState();
        assertEquals(0, js("__animationCount()").asInt());
    }

    @Test
    void partialWebAnimationFailureCancelsWorkButKeepsTheTruthfulState()
            throws IOException {
        boot(false);
        Value root = js("globalThis.__root = __makeTransition(); __root");
        api.getMember("init").execute(root);
        js("__setAnimationFailure(2); __root.__control.__fire('click', {})");

        assertToState();
        assertEquals(1, js("__animationCount()").asInt());
        assertEquals(1, js("__cancelledAnimationCount()").asInt(),
            "a half-started pair must not keep running after its partner fails");
    }

    @Test
    void destroyRestoresTheNoJsRepresentationAndAllowsCleanReinitialization()
            throws IOException {
        boot(false);
        Value root = js("globalThis.__root = __makeTransition(); __root");
        api.getMember("init").execute(root);
        js("__root.__control.__fire('click', {})");

        api.getMember("destroy").execute(root);

        assertFalse(root.getMember("classList").getMember("contains")
            .execute("lx-transition--ready").asBoolean());
        assertFalse(root.getMember("classList").getMember("contains")
            .execute("lx-transition--to").asBoolean());
        assertTrue(js("__root.__from.getAttribute('aria-hidden') === null").asBoolean());
        assertTrue(js("__root.__to.getAttribute('aria-hidden') === null").asBoolean());
        assertFalse(js("__root.__from.inert").asBoolean());
        assertFalse(js("__root.__to.inert").asBoolean());
        assertEquals(0, js("__root.__control.__listenerCount('click')").asInt());
        assertEquals(0, js("__root.__control.__listenerCount('focus')").asInt());
        assertEquals(0, js("__root.__stage.__listenerCount('mouseenter')").asInt());
        assertEquals(0, js("__observerCount()").asInt());

        api.getMember("init").execute(root);
        assertEquals(1, js("__root.__control.__listenerCount('click')").asInt());
        assertEquals(1, js("__observerCount()").asInt());
    }

    @Test
    void detachmentObserverCancelsWorkAndTearsDownListeners() throws IOException {
        boot(false);
        Value root = js("globalThis.__root = __makeTransition(); __root");
        api.getMember("init").execute(root);
        js("__root.__control.__fire('click', {})");
        assertEquals(2, js("__animationCount()").asInt());

        js("__root.isConnected = false; __mutate()");

        assertEquals(0, js("__root.__control.__listenerCount('click')").asInt());
        assertEquals(0, js("__root.__stage.__listenerCount('mouseenter')").asInt());
        assertTrue(js("__cancelledAnimationCount()").asInt() >= 2);
        assertEquals(0, js("__observerCount()").asInt());
    }

    @Test
    void documentAdoptionReleasesTheOriginalDocumentObserver() throws IOException {
        boot(false);
        Value root = js("globalThis.__root = __makeTransition(); __root");
        api.getMember("init").execute(root);

        js("__root.ownerDocument = {}; __mutate()");

        assertEquals(0, js("__root.__control.__listenerCount('click')").asInt());
        assertEquals(0, js("__root.__stage.__listenerCount('mouseenter')").asInt());
        assertEquals(0, js("__observerCount()").asInt());
        assertFalse(root.getMember("classList").getMember("contains")
            .execute("lx-transition--ready").asBoolean());
    }

    @Test
    void runtimeContainsNoTimerLoopOrMarkupParserSink() {
        String source = InteractiveMath.runtimeJs();
        assertFalse(source.contains("setTimeout"));
        assertFalse(source.contains("setInterval"));
        assertFalse(source.contains("requestAnimationFrame"));
        assertFalse(source.contains("inner" + "HTML"));
        assertTrue(source.contains("MutationObserver"));
        assertTrue(source.contains("preventDefault"));
    }

    private void assertToState() {
        assertTrue(js("__root.classList.contains('lx-transition--to')").asBoolean());
        assertEquals("true", js("__root.__from.getAttribute('aria-hidden')").asString());
        assertEquals("false", js("__root.__to.getAttribute('aria-hidden')").asString());
        assertEquals("true", js("__root.__control.getAttribute('aria-expanded')").asString());
        assertEquals("Show initial equation", js("__root.__control.textContent").asString());
    }

    private void assertFromState() {
        assertFalse(js("__root.classList.contains('lx-transition--to')").asBoolean());
        assertEquals("false", js("__root.__from.getAttribute('aria-hidden')").asString());
        assertEquals("true", js("__root.__to.getAttribute('aria-hidden')").asString());
        assertEquals("false", js("__root.__control.getAttribute('aria-expanded')").asString());
        assertEquals("Show alternate equation", js("__root.__control.textContent").asString());
    }

    private void assertPreviewState() {
        assertTrue(js("__root.classList.contains('lx-transition--to')").asBoolean());
        assertEquals("true", js("__root.__from.getAttribute('aria-hidden')").asString());
        assertEquals("false", js("__root.__to.getAttribute('aria-hidden')").asString());
        assertEquals("true", js("__root.__control.getAttribute('aria-expanded')").asString());
        assertEquals("Keep alternate equation",
            js("__root.__control.textContent").asString());
    }

    private Value js(String script) {
        return context.eval("js", script);
    }

    private static Source source(String resource, String name) throws IOException {
        try (InputStream input = InteractiveRuntimeTest.class.getResourceAsStream(resource)) {
            assertNotNull(input, "missing classpath resource " + resource);
            return Source.newBuilder("js",
                new String(input.readAllBytes(), StandardCharsets.UTF_8), name).buildLiteral();
        }
    }
}
