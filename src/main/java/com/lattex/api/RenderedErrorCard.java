package com.lattex.api;

import com.lattex.font.SfntFont;
import com.lattex.layout.Layout;
import com.lattex.layout.LayoutContext;
import com.lattex.layout.LayoutEngine;
import com.lattex.layout.PositionedGlyph;
import com.lattex.layout.Rule;
import com.lattex.parse.MathNode.TextRun;
import com.lattex.parse.MathNode.TextStyle;
import com.lattex.parse.OutputLegality;
import com.lattex.svg.SvgEmitter;
import java.util.ArrayList;
import java.util.List;

/**
 * Renderer-owned, opt-in diagnostic card. Diagnostic strings become direct
 * {@link TextRun} geometry and rules; they are never concatenated into LaTeX or
 * reparsed. All source-dependent rows are legality-checked and code-point capped
 * before layout, and the existing capped {@link SvgEmitter} is the only serializer.
 */
final class RenderedErrorCard {

    static final int MAX_MESSAGE_CODE_POINTS = 160;
    static final int MAX_SOURCE_EXCERPT_CODE_POINTS = 160;
    static final int MAX_CARET_CODE_POINTS = 160;

    private static final double FONT_SIZE = 16.0;
    private static final double PADDING = 14.0;
    private static final double ROW_GAP = 8.0;
    private static final double BORDER = 2.0;
    private static final double MIN_WIDTH = 320.0;

    private static final Color TEXT_COLOR = new Color.Hex("#3f1511");
    private static final Color MUTED_COLOR = new Color.Hex("#69413b");
    private static final Color ACCENT_COLOR = new Color.Hex("#b42318");

    private RenderedErrorCard() {
    }

    static String render(String source, Diagnostics diagnostics, SfntFont font) {
        CardText text = cardText(source, diagnostics);
        List<Row> rows = new ArrayList<>();
        rows.add(new Row(text.outcome(), TextStyle.BOLD, ACCENT_COLOR, null));
        rows.add(new Row(text.message(), TextStyle.ROMAN, TEXT_COLOR, null));
        if (text.excerpt() != null) {
            rows.add(new Row(text.excerpt(), TextStyle.MONO, MUTED_COLOR, null));
        }
        if (text.caret() != null) {
            // LayoutEngine intentionally does not emit a glyph for a regular space.
            // Measure the actual bounded excerpt prefix and translate the caret glyph;
            // relying on a run of leading spaces would render every caret at column 0.
            rows.add(new Row("^", TextStyle.MONO, ACCENT_COLOR,
                prefixBeforeCaret(text.excerpt(), text.caret())));
        }

        LayoutContext context = new LayoutContext(
            font, font.mathConstants(), FONT_SIZE, MathStyle.TEXT, false);
        List<PositionedGlyph> glyphs = new ArrayList<>();
        List<Rule> rules = new ArrayList<>();
        double contentWidth = 0.0;
        double rowTop = PADDING;

        for (Row row : rows) {
            Layout laidOut = LayoutEngine.layout(new TextRun(row.text(), row.style()), context);
            double indent = 0.0;
            if (row.indentText() != null && !row.indentText().isEmpty()) {
                indent = LayoutEngine.layout(
                    new TextRun(row.indentText(), row.style()), context).width();
            }
            double dx = PADDING + indent - laidOut.minX();
            double dy = rowTop - laidOut.minY();
            for (PositionedGlyph glyph : laidOut.glyphs()) {
                glyphs.add(new PositionedGlyph(
                    glyph.glyphId(),
                    glyph.originX() + dx,
                    glyph.baselineY() + dy,
                    glyph.scale(),
                    row.color(),
                    glyph.sourceCodePoint(),
                    glyph.fenceDepth()));
            }
            for (Rule rule : laidOut.rules()) {
                rules.add(translated(rule, dx, dy));
            }
            contentWidth = Math.max(contentWidth, indent + laidOut.width());
            rowTop += laidOut.height() + ROW_GAP;
        }

        double width = Math.max(MIN_WIDTH, contentWidth + 2.0 * PADDING);
        double height = Math.max(2.0 * PADDING + FONT_SIZE,
            rowTop - ROW_GAP + PADDING);
        rules.add(new Rule(0.0, 0.0, width, BORDER, ACCENT_COLOR));
        rules.add(new Rule(0.0, height - BORDER, width, BORDER, ACCENT_COLOR));
        rules.add(new Rule(0.0, BORDER, BORDER, height - 2.0 * BORDER, ACCENT_COLOR));
        rules.add(new Rule(width - BORDER, BORDER, BORDER,
            height - 2.0 * BORDER, ACCENT_COLOR));

        Layout card = new Layout(glyphs, rules, 0.0, 0.0, width, height);
        return SvgEmitter.emit(card, font, "LatteX render error", TEXT_COLOR);
    }

    /**
     * The auditable privacy seam: these are the only strings allowed to become
     * card geometry. Deliberately has no Diagnostics.detail/caretString access.
     */
    static CardText cardText(String source, Diagnostics diagnostics) {
        String outcome = boundedLegalRow(
            diagnostics.outcome() == null ? null : diagnostics.outcome().name(),
            MAX_MESSAGE_CODE_POINTS, "RENDER_BUG");
        String message = boundedLegalRow(
            diagnostics.message(), MAX_MESSAGE_CODE_POINTS, "Render failed.");
        Excerpt excerpt = sourceExcerpt(source, diagnostics.offset());
        return new CardText(outcome, message,
            excerpt == null ? null : excerpt.text(),
            excerpt == null ? null : excerpt.caret());
    }

    private static Excerpt sourceExcerpt(String source, int offset) {
        if (source == null || offset < 0 || offset > source.length()) {
            return null;
        }

        int lineStart = offset == 0 ? 0 : source.lastIndexOf('\n', offset - 1) + 1;
        int lineEnd = source.indexOf('\n', offset);
        if (lineEnd < 0) {
            lineEnd = source.length();
        }
        if (lineEnd > lineStart && source.charAt(lineEnd - 1) == '\r') {
            lineEnd--;
        }

        String line = source.substring(lineStart, lineEnd);
        int within = Math.max(0, Math.min(offset - lineStart, line.length()));
        if (within > 0 && within < line.length()
                && Character.isHighSurrogate(line.charAt(within - 1))
                && Character.isLowSurrogate(line.charAt(within))) {
            within--;
        }

        int totalCodePoints = line.codePointCount(0, line.length());
        int caretCodePoint = line.codePointCount(0, within);
        int contentBudget = totalCodePoints > MAX_SOURCE_EXCERPT_CODE_POINTS
            ? MAX_SOURCE_EXCERPT_CODE_POINTS - 2
            : MAX_SOURCE_EXCERPT_CODE_POINTS;
        int startCodePoint = 0;
        if (totalCodePoints > contentBudget) {
            startCodePoint = Math.max(0,
                Math.min(caretCodePoint - contentBudget / 2,
                    totalCodePoints - contentBudget));
        }
        int endCodePoint = Math.min(totalCodePoints, startCodePoint + contentBudget);
        int startChar = line.offsetByCodePoints(0, startCodePoint);
        int endChar = line.offsetByCodePoints(0, endCodePoint);
        int caretChar = Math.max(startChar, Math.min(within, endChar));

        try {
            String visible = normalizeWhitespace(
                OutputLegality.sanitize(line.substring(startChar, endChar)));
            String visiblePrefix = normalizeWhitespace(
                OutputLegality.sanitize(line.substring(startChar, caretChar)));
            boolean leftEllipsis = startCodePoint > 0;
            boolean rightEllipsis = endCodePoint < totalCodePoints;
            String excerpt = (leftEllipsis ? "\u2026" : "")
                + visible + (rightEllipsis ? "\u2026" : "");
            int caretColumn = (leftEllipsis ? 1 : 0)
                + visiblePrefix.codePointCount(0, visiblePrefix.length());
            caretColumn = Math.min(MAX_CARET_CODE_POINTS - 1, caretColumn);
            return new Excerpt(excerpt, " ".repeat(caretColumn) + "^");
        } catch (RuntimeException malformedRow) {
            return new Excerpt("Source excerpt unavailable.", null);
        }
    }

    private static String boundedLegalRow(String raw, int maxCodePoints,
            String fallback) {
        if (raw == null) {
            return fallback;
        }
        int contentLimit = Math.max(1, maxCodePoints - 1);
        int end = advanceCodePoints(raw, contentLimit);
        boolean truncated = end < raw.length();
        try {
            String clean = normalizeWhitespace(OutputLegality.sanitize(raw.substring(0, end)));
            if (clean.isBlank()) {
                return fallback;
            }
            return clean + (truncated ? "\u2026" : "");
        } catch (RuntimeException malformedRow) {
            return fallback;
        }
    }

    /** The visible excerpt prefix whose measured width anchors the caret glyph. */
    private static String prefixBeforeCaret(String excerpt, String caret) {
        int caretColumn = Math.max(0, caret.codePointCount(0, caret.indexOf('^')));
        int available = excerpt.codePointCount(0, excerpt.length());
        int prefixCodePoints = Math.min(caretColumn, available);
        return excerpt.substring(0, excerpt.offsetByCodePoints(0, prefixCodePoints));
    }

    /** Advance by at most {@code limit} code points without splitting an astral pair. */
    private static int advanceCodePoints(String value, int limit) {
        int index = 0;
        int count = 0;
        while (index < value.length() && count < limit) {
            char current = value.charAt(index);
            if (Character.isHighSurrogate(current)
                    && index + 1 < value.length()
                    && Character.isLowSurrogate(value.charAt(index + 1))) {
                index += 2;
            } else {
                index++;
            }
            count++;
        }
        return index;
    }

    /** One visual row: preserve characters, but make legal line whitespace inert. */
    private static String normalizeWhitespace(String value) {
        StringBuilder normalized = new StringBuilder(value.length());
        boolean previousSpace = false;
        for (int i = 0; i < value.length();) {
            int codePoint = value.codePointAt(i);
            i += Character.charCount(codePoint);
            if (Character.isWhitespace(codePoint)) {
                if (!previousSpace) {
                    normalized.append(' ');
                    previousSpace = true;
                }
            } else {
                normalized.appendCodePoint(codePoint);
                previousSpace = false;
            }
        }
        return normalized.toString();
    }

    private static Rule translated(Rule rule, double dx, double dy) {
        if (!rule.isPolygon()) {
            return new Rule(rule.x() + dx, rule.y() + dy,
                rule.width(), rule.height(), rule.color());
        }
        double[] points = rule.polygon();
        for (int i = 0; i < points.length; i += 2) {
            points[i] += dx;
            points[i + 1] += dy;
        }
        return Rule.polygon(points, rule.color());
    }

    static record CardText(String outcome, String message, String excerpt, String caret) {
    }

    private record Excerpt(String text, String caret) {
    }

    private record Row(String text, TextStyle style, Color color, String indentText) {
    }
}
