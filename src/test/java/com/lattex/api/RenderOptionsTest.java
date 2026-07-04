package com.lattex.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lattex.layout.MathStyle;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the L1 styling boundary: {@link RenderOptions} / {@link Color}
 * parsing + validation, and the {@link LatteX#render(String, RenderOptions)}
 * overload visibly applying scale, fill color, and math style — while the
 * no-options {@link LatteX#render(String)} path stays byte-identical.
 */
class RenderOptionsTest {

    // ---- parseScale: buckets, bounds, clamp, rejection ----

    @Test
    void parseScaleBuckets() {
        assertEquals(0.8, RenderOptions.parseScale("sm"));
        assertEquals(1.0, RenderOptions.parseScale("md"));
        assertEquals(1.4, RenderOptions.parseScale("lg"));
        // Case-insensitive + whitespace-tolerant.
        assertEquals(1.4, RenderOptions.parseScale("  LG "));
    }

    @Test
    void parseScaleNumeric() {
        assertEquals(1.0, RenderOptions.parseScale("1"));
        assertEquals(2.5, RenderOptions.parseScale("2.5"));
        assertEquals(0.5, RenderOptions.parseScale("0.5"));
    }

    @Test
    void parseScaleClampsToBounds() {
        // Below MIN clamps up; a huge in-pattern value clamps down to MAX.
        assertEquals(RenderOptions.MIN_SCALE, RenderOptions.parseScale("0"));
        assertEquals(RenderOptions.MAX_SCALE, RenderOptions.parseScale("99"));
    }

    @Test
    void parseScaleRejectsGarbage() {
        assertThrows(IllegalArgumentException.class, () -> RenderOptions.parseScale(null));
        assertThrows(IllegalArgumentException.class, () -> RenderOptions.parseScale(""));
        assertThrows(IllegalArgumentException.class, () -> RenderOptions.parseScale("huge"));
        assertThrows(IllegalArgumentException.class, () -> RenderOptions.parseScale("-1"));
        assertThrows(IllegalArgumentException.class, () -> RenderOptions.parseScale("1e3"));
        // Too many digits for the anchored pattern (guards against overflow input).
        assertThrows(IllegalArgumentException.class, () -> RenderOptions.parseScale("100"));
    }

    @Test
    void recordConstructorRejectsOutOfRangeScale() {
        assertThrows(IllegalArgumentException.class,
            () -> new RenderOptions(0.0, Color.BLACK, MathStyle.DISPLAY));
        assertThrows(IllegalArgumentException.class,
            () -> new RenderOptions(1000.0, Color.BLACK, MathStyle.DISPLAY));
        assertThrows(IllegalArgumentException.class,
            () -> new RenderOptions(Double.NaN, Color.BLACK, MathStyle.DISPLAY));
        assertThrows(NullPointerException.class,
            () -> new RenderOptions(1.0, null, MathStyle.DISPLAY));
        assertThrows(NullPointerException.class,
            () -> new RenderOptions(1.0, Color.BLACK, null));
    }

    // ---- Color: validation + canonicalization ----

    @Test
    void colorParsesCurrentColor() {
        assertSame(Color.CURRENT, Color.parse("currentColor"));
        assertSame(Color.CURRENT, Color.parse("CURRENTCOLOR"));
        assertEquals("currentColor", Color.CURRENT.svgValue());
    }

    @Test
    void colorCanonicalizesHex() {
        // Uppercase -> lowercase.
        assertEquals("#aabbcc", Color.parse("#AABBCC").svgValue());
        // 3-digit shorthand -> 6-digit.
        assertEquals("#aabbcc", Color.parse("#abc").svgValue());
        assertEquals("#ff0000", Color.parse("#F00").svgValue());
        // Whitespace tolerated.
        assertEquals("#123456", Color.parse("  #123456 ").svgValue());
    }

    @Test
    void colorRejectsGarbage() {
        assertThrows(IllegalArgumentException.class, () -> Color.parse(null));
        assertThrows(IllegalArgumentException.class, () -> Color.parse("red"));
        assertThrows(IllegalArgumentException.class, () -> Color.parse("#12"));
        assertThrows(IllegalArgumentException.class, () -> Color.parse("#12345"));
        assertThrows(IllegalArgumentException.class, () -> Color.parse("#1234567"));
        assertThrows(IllegalArgumentException.class, () -> Color.parse("123456"));
        assertThrows(IllegalArgumentException.class, () -> Color.parse("#gggggg"));
        // A Hex can never hold a non-canonical value.
        assertThrows(IllegalArgumentException.class, () -> new Color.Hex("#ABCDEF"));
        assertThrows(IllegalArgumentException.class, () -> new Color.Hex("#abc"));
    }

    // ---- parseMathStyle ----

    @Test
    void parseMathStyleNames() {
        assertEquals(MathStyle.DISPLAY, RenderOptions.parseMathStyle("display"));
        assertEquals(MathStyle.TEXT, RenderOptions.parseMathStyle("TEXT"));
        assertEquals(MathStyle.SCRIPT, RenderOptions.parseMathStyle(" script "));
        assertEquals(MathStyle.SCRIPT_SCRIPT, RenderOptions.parseMathStyle("scriptscript"));
        assertThrows(IllegalArgumentException.class, () -> RenderOptions.parseMathStyle("tiny"));
        assertThrows(IllegalArgumentException.class, () -> RenderOptions.parseMathStyle(null));
    }

    // ---- render(latex, opts) applies each knob ----

    @Test
    void defaultRenderIsByteIdenticalToNoOptions() {
        assertEquals(LatteX.render("x^2"),
            LatteX.render("x^2", RenderOptions.defaults()),
            "the no-options path must equal render(latex, defaults())");
    }

    @Test
    void defaultFillIsCurrentColor() {
        String svg = LatteX.render("x^2", RenderOptions.defaults());
        assertTrue(svg.contains("fill=\"currentColor\""),
            "default fill is currentColor (inherits the surrounding text color)");
        assertFalse(svg.contains("fill=\"#000000\""), "no hardcoded black default");
    }

    @Test
    void colorAppearsInSvgFill() {
        String hex = LatteX.render("x^2", RenderOptions.defaults().withColor(Color.parse("#ff0000")));
        assertTrue(hex.contains("fill=\"#ff0000\""), "hex color threads into the <g> fill");
        assertFalse(hex.contains("fill=\"#000000\""), "no leftover default fill");

        String current = LatteX.render("x^2", RenderOptions.defaults().withColor(Color.CURRENT));
        assertTrue(current.contains("fill=\"currentColor\""), "currentColor threads into the fill");
    }

    @Test
    void scaleChangesDimensions() {
        double base = width(LatteX.render("x^2", RenderOptions.defaults()));
        double big = width(LatteX.render("x^2", RenderOptions.defaults().withScale(2.0)));
        double small = width(LatteX.render("x^2", RenderOptions.defaults().withScale(0.5)));
        assertTrue(big > base, "scale 2.0 widens the output (" + big + " > " + base + ")");
        assertTrue(small < base, "scale 0.5 narrows the output (" + small + " < " + base + ")");
    }

    @Test
    void mathStyleChangesLayout() {
        // Display vs script style produce visibly different geometry (script shrinks).
        String display = LatteX.render("\\frac{a}{b}", RenderOptions.defaults());
        String script = LatteX.render("\\frac{a}{b}",
            RenderOptions.defaults().withMathStyle(MathStyle.SCRIPT));
        assertNotEquals(display, script, "changing the top-level math style changes the SVG");
        assertTrue(width(script) < width(display),
            "script style is smaller than display style");
    }

    // ---- inline() / display() api selectors + LatteX.renderInline (plan 7c0dd924) ----

    @Test
    void inlineAndDisplaySelectorsSetTheMathStyle() {
        // api-only selectors — a caller gets TEXT/DISPLAY without naming the (non-exported) type.
        assertEquals(MathStyle.DISPLAY, RenderOptions.defaults().mathStyle(), "default is display");
        assertEquals(MathStyle.TEXT, RenderOptions.defaults().inline().mathStyle());
        assertEquals(MathStyle.DISPLAY, RenderOptions.defaults().inline().display().mathStyle());
        // They preserve the other knobs.
        RenderOptions o = RenderOptions.defaults().withScale(1.4).inline();
        assertEquals(1.4, o.scale());
        assertEquals(MathStyle.TEXT, o.mathStyle());
    }

    @Test
    void renderInlineIsTextStyledAndShorterThanDisplay() {
        // A display fraction stacks numerator over denominator at full size; the inline (text)
        // fraction is set smaller, so it is shorter — right for math on a prose line.
        String frac = "\\frac{a}{b}";
        String inline = LatteX.renderInline(frac);
        String display = LatteX.render(frac); // defaults to display
        assertNotEquals(inline, display, "inline vs display render differently");
        assertTrue(height(inline) < height(display),
            "inline fraction is shorter than the display fraction: "
                + height(inline) + " vs " + height(display));
    }

    @Test
    void renderInlineEqualsExplicitInlineOptions() {
        String expr = "\\sum_{i=1}^{n} i";
        assertEquals(LatteX.render(expr, RenderOptions.defaults().inline()),
            LatteX.renderInline(expr), "renderInline == render(latex, defaults().inline())");
    }

    /** Extracts the numeric {@code width="…"} from an emitted SVG. */
    private static double width(String svg) {
        Matcher m = Pattern.compile("width=\"([0-9.]+)\"").matcher(svg);
        assertTrue(m.find(), "svg has a width attribute");
        return Double.parseDouble(m.group(1));
    }

    /** Extracts the numeric {@code height="…"} from an emitted SVG. */
    private static double height(String svg) {
        Matcher m = Pattern.compile("height=\"([0-9.]+)\"").matcher(svg);
        assertTrue(m.find(), "svg has a height attribute");
        return Double.parseDouble(m.group(1));
    }
}
