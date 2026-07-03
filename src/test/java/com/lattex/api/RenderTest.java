package com.lattex.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * End-to-end skeleton tests: rendering {@code x^2} produces real glyph paths and
 * emits ONLY the minimal, sanitizer-safe element/attribute alphabet.
 */
class RenderTest {

    // The named minimal subset (CONTRIBUTING §5).
    private static final Set<String> ALLOWED_ELEMENTS = Set.of("svg", "g", "path", "rect");
    private static final Set<String> ALLOWED_ATTRS = Set.of(
        "viewBox", "width", "height", "xmlns", "role", "aria-label", // svg
        "transform", "fill",                                          // g / path
        "d", "stroke", "stroke-width",                               // path
        "x", "y");                                                    // rect

    // Elements that must NEVER appear.
    private static final List<String> FORBIDDEN_ELEMENTS = List.of(
        "use", "defs", "symbol", "text", "foreignObject", "script", "style",
        "animate", "image", "a");

    @Test
    void rendersTwoGlyphPaths() {
        String svg = LatteX.render("x^2");
        int paths = countOccurrences(svg, "<path");
        assertEquals(2, paths, "one <path> per glyph (x and the superscript 2)");
        assertTrue(svg.contains("role=\"img\""), "has role=img");
        assertTrue(svg.contains("aria-label=\"x squared\""), "aria-label describes the formula");
        assertTrue(svg.contains("viewBox="), "has a viewBox");
        // Both paths carry real, non-empty outline data with curve commands.
        assertTrue(svg.contains("Q"), "glyph paths contain quadratic Bézier commands");
    }

    @Test
    void emitsOnlyAllowlistedElementsAndAttributes() {
        String svg = LatteX.render("x^2");

        // No forbidden constructs anywhere.
        for (String forbidden : FORBIDDEN_ELEMENTS) {
            assertFalse(svg.contains("<" + forbidden), "must not emit <" + forbidden + ">");
        }
        assertFalse(svg.contains("href"), "must not emit any href");
        assertFalse(svg.contains("data:"), "must not emit any data: URI");

        // Every element and attribute must be inside the allow-list.
        Matcher tag = Pattern.compile("<([a-zA-Z][a-zA-Z0-9]*)((?:\\s+[^>]*)?)/?>").matcher(svg);
        int tags = 0;
        while (tag.find()) {
            tags++;
            String element = tag.group(1);
            assertTrue(ALLOWED_ELEMENTS.contains(element),
                "element out of alphabet: <" + element + ">");
            Matcher attr = Pattern.compile("([a-zA-Z][a-zA-Z-]*)\\s*=")
                .matcher(tag.group(2));
            while (attr.find()) {
                String name = attr.group(1);
                assertTrue(ALLOWED_ATTRS.contains(name),
                    "attribute out of alphabet on <" + element + ">: " + name);
            }
        }
        assertTrue(tags >= 4, "scanned the svg/g/path/path tags: " + tags);
    }

    private static int countOccurrences(String haystack, String needle) {
        int count = 0;
        for (int i = haystack.indexOf(needle); i >= 0; i = haystack.indexOf(needle, i + 1)) {
            count++;
        }
        return count;
    }
}
