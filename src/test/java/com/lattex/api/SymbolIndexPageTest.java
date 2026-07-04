package com.lattex.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lattex.parse.MathParser;
import com.lattex.parse.MathParser.Category;
import com.lattex.parse.MathParser.SupportedCommand;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Generates {@code examples/symbol-index.html} — the exhaustive, drift-free
 * index of EVERY command LatteX supports. Unlike the curated
 * {@code gallery-specimen.html}, this page is auto-enumerated from the parser's
 * own command tables via {@link MathParser#supportedCommands()}, so it always
 * reflects exactly what the renderer accepts and can never drift: add a command
 * to a table and it appears here on the next build.
 *
 * <p>Each cell renders one command live to a self-contained {@code svg/g/path/
 * rect} subset (no external assets), grouped by {@link Category} with per-group
 * counts and a grand total. The test asserts one rendered SVG per enumerated
 * command — enforcing the "exhaustive + drift-free" property.
 */
class SymbolIndexPageTest {

    @Test
    void writesSymbolIndex() throws IOException {
        List<SupportedCommand> commands = MathParser.supportedCommands();
        assertTrue(commands.size() >= 300,
            "a broad, exhaustive index, got " + commands.size());

        // Group by category, preserving the enum's natural display order.
        Map<Category, List<SupportedCommand>> byCategory = new EnumMap<>(Category.class);
        for (SupportedCommand c : commands) {
            byCategory.computeIfAbsent(c.category(), k -> new ArrayList<>()).add(c);
        }

        StringBuilder sections = new StringBuilder();
        int total = 0;
        for (Category cat : Category.values()) {
            List<SupportedCommand> group = byCategory.get(cat);
            if (group == null || group.isEmpty()) {
                continue;
            }
            // Stable, human-friendly ordering within a category.
            group.sort((a, b) -> a.command().compareToIgnoreCase(b.command()));
            StringBuilder cells = new StringBuilder();
            for (SupportedCommand c : group) {
                String svg = LatteX.render(c.renderTemplate());
                cells.append(cell(c.command(), svg)).append('\n');
                total++;
            }
            sections.append(section(cat.title(), group.size(), cells.toString()));
        }

        String html = page(total, sections.toString());
        Path out = Path.of("examples", "symbol-index.html");
        Files.createDirectories(out.getParent());
        Files.writeString(out, html);

        String written = Files.readString(out);
        assertTrue(written.contains("<svg"), "index embeds SVGs");
        // The core drift-free invariant: exactly one rendered SVG per command.
        assertEquals(commands.size(), total, "every enumerated command rendered");
        assertEquals(commands.size(), countOccurrences(written, "<svg"),
            "one rendered SVG per enumerated command");
    }

    // ---- HTML template (self-contained, single file, no external assets) ----

    private static String cell(String command, String svg) {
        return """
                <figure class="cell">
                  <div class="render">
            %s
                  </div>
                  <code class="src">%s</code>
                </figure>""".formatted(indent(svg), escapeHtml(command));
    }

    private static String section(String title, int count, String cells) {
        return """
            <section class="cat">
              <header class="cat-head">
                <h2>%s</h2>
                <span class="count">%d</span>
              </header>
              <div class="grid">
            %s
              </div>
            </section>
            """.formatted(escapeHtml(title), count,
                indentBlock(cells, "        "));
    }

    private static String page(int total, String sections) {
        return """
            <!doctype html>
            <html lang="en">
            <head>
              <meta charset="utf-8">
              <meta name="viewport" content="width=device-width, initial-scale=1">
              <title>LatteX — command index</title>
              %s
            </head>
            <body>
              <main>
                <header class="masthead">
                  <p class="eyebrow">LatteX · clean-room LaTeX → SVG</p>
                  <h1>Command index</h1>
                  <p class="lede">Every command the parser supports, auto-enumerated
                    from its own command tables — <strong>%d commands</strong>, each
                    rendered live to a self-contained
                    <code>svg/g/path/rect</code> subset. Generated on every build,
                    so it can never drift from what LatteX actually accepts.</p>
                </header>
            %s
              </main>
            </body>
            </html>
            """.formatted(STYLE, total, indentBlock(sections, "    "));
    }

    private static final String STYLE = """
        <style>
            :root {
              color-scheme: light dark;
              --bg: #f5f7f7; --panel: #ffffff; --ink: #16211f; --muted: #6b7d79;
              --line: #e2e8e6; --accent: #16897a; --chip: #eef3f1; --chip-line: #dde5e2;
            }
            @media (prefers-color-scheme: dark) {
              :root {
                --bg: #0e1413; --panel: #161d1c; --ink: #e8efec; --muted: #8ba09a;
                --line: #26302e; --accent: #4fd1bd; --chip: #1b2422; --chip-line: #2b3634;
              }
            }
            :root[data-theme="light"] {
              color-scheme: light;
              --bg: #f5f7f7; --panel: #ffffff; --ink: #16211f; --muted: #6b7d79;
              --line: #e2e8e6; --accent: #16897a; --chip: #eef3f1; --chip-line: #dde5e2;
            }
            :root[data-theme="dark"] {
              color-scheme: dark;
              --bg: #0e1413; --panel: #161d1c; --ink: #e8efec; --muted: #8ba09a;
              --line: #26302e; --accent: #4fd1bd; --chip: #1b2422; --chip-line: #2b3634;
            }
            * { box-sizing: border-box; }
            body { margin: 0; background: var(--bg); color: var(--ink);
                   font-family: ui-sans-serif, system-ui, -apple-system, sans-serif;
                   line-height: 1.5; }
            main { max-width: 1160px; margin: 0 auto; padding: 3.5rem 1.5rem 5rem; }
            .masthead { text-align: center; margin-bottom: 2.5rem; }
            .eyebrow { font-family: ui-monospace, monospace; font-size: .72rem;
                       letter-spacing: .2em; text-transform: uppercase;
                       color: var(--accent); margin: 0 0 .6rem; }
            h1 { font-size: 2.4rem; margin: 0 0 .8rem; letter-spacing: -.02em; }
            .lede { max-width: 60ch; margin: 0 auto; color: var(--muted); font-size: 1.02rem; }
            .lede code, .lede strong { color: var(--ink); }
            .lede code { font-family: ui-monospace, monospace; font-size: .92em; }
            .cat { margin-top: 2.6rem; }
            .cat-head { display: flex; align-items: baseline; gap: .8rem;
                        padding-bottom: .6rem; margin-bottom: 1.2rem;
                        border-bottom: 1px solid var(--line); }
            .cat-head h2 { font-size: 1.15rem; margin: 0; letter-spacing: -.01em; }
            .count { font-family: ui-monospace, monospace; font-size: .74rem;
                     color: var(--accent); background: var(--chip);
                     border: 1px solid var(--chip-line); border-radius: 999px;
                     padding: .15rem .55rem; }
            .grid { display: grid; gap: .7rem;
                    grid-template-columns: repeat(auto-fill, minmax(132px, 1fr)); }
            .cell { display: flex; flex-direction: column; align-items: center;
                    justify-content: space-between; gap: .7rem; margin: 0;
                    background: var(--panel); border: 1px solid var(--line);
                    border-radius: 10px; padding: 1rem .6rem .55rem;
                    transition: border-color .15s ease, transform .15s ease; }
            .cell:hover { border-color: var(--accent); transform: translateY(-2px); }
            .render { flex: 1; display: flex; align-items: center; justify-content: center;
                      min-height: 52px; width: 100%; }
            .render svg { max-height: 52px; max-width: 100%; width: auto; height: auto;
                          display: block; }
            @media (prefers-color-scheme: dark) {
              /* glyph paths are #000; invert into the dark panel */
              .render svg { filter: invert(1) hue-rotate(180deg); }
            }
            :root[data-theme="light"] .render svg { filter: none; }
            :root[data-theme="dark"] .render svg { filter: invert(1) hue-rotate(180deg); }
            .src { font-family: ui-monospace, monospace; font-size: .7rem;
                   color: var(--muted); background: var(--chip);
                   border: 1px solid var(--chip-line); border-radius: 6px;
                   padding: .25rem .45rem; max-width: 100%;
                   overflow-x: auto; white-space: nowrap; }
          </style>""";

    private static String indent(String svg) {
        return svg.lines().map(l -> "        " + l)
            .reduce((a, b) -> a + "\n" + b).orElse(svg);
    }

    private static String indentBlock(String block, String pad) {
        return block.lines().map(l -> l.isEmpty() ? l : pad + l)
            .reduce((a, b) -> a + "\n" + b).orElse(block);
    }

    private static String escapeHtml(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static int countOccurrences(String haystack, String needle) {
        int count = 0;
        for (int i = haystack.indexOf(needle); i >= 0; i = haystack.indexOf(needle, i + 1)) {
            count++;
        }
        return count;
    }
}
