package com.lattexprobe;

import com.lattex.api.Color;
import com.lattex.api.LatteX;
import com.lattex.api.LatteXException;
import com.lattex.api.MathStyle;
import com.lattex.api.RenderOptions;
import java.util.Map;

/**
 * A modular consumer of LatteX that names ONLY exported {@code com.lattex.api} types:
 * {@link LatteX} and {@link LatteXException}.
 *
 * <p>Before {@code com.lattex.api.LatteXException} existed this class could not be
 * written against the exported surface: the exception the render methods throw lives in
 * the non-exported {@code com.lattex.parse} package, so a modular consumer could not
 * name it, and its only fallback — {@link IllegalArgumentException} — is ALSO thrown for
 * a bad parameter, so malformed-LaTeX and bad-argument failures were indistinguishable.
 *
 * <p>{@link #fragmentBadSize} is the discriminator half: it proves the two failure modes
 * are now separable from a module, using only exported names.
 *
 * <p>{@link #canonicalRenderOptions} proves a second, independent boundary: before
 * {@code MathStyle} moved into {@code com.lattex.api}, {@link RenderOptions} — itself
 * exported — had a record component of the NON-exported type {@code com.lattex.layout.MathStyle}.
 * A modular consumer could reach {@code RenderOptions} but could neither name the type
 * {@code mathStyle()} returns nor call the canonical constructor for any argument list.
 * With {@code MathStyle} moved, both are now possible using only exported names.
 */
public final class Consumer {

    private Consumer() {
    }

    /**
     * Names {@link MathStyle} directly and calls {@link RenderOptions}'s canonical
     * (seven-arg) constructor — both impossible from a module before {@code MathStyle}
     * moved from the non-exported {@code com.lattex.layout} into {@code com.lattex.api}.
     *
     * @return {@code "MATHSTYLE:"} + the round-tripped {@link RenderOptions#mathStyle()}
     */
    public static String canonicalRenderOptions() {
        RenderOptions opts = new RenderOptions(1.0, Color.CURRENT, MathStyle.TEXT,
            Map.of(), false, false, false);
        return "MATHSTYLE:" + opts.mathStyle();
    }

    /**
     * Renders {@code latex}, catching the render failure via the exported supertype.
     *
     * @return {@code "OK:"} + the SVG, or {@code "CAUGHT:"} + the failure message
     */
    public static String render(String latex) {
        try {
            return "OK:" + LatteX.render(latex);
        } catch (LatteXException e) {
            return "CAUGHT:" + e.getMessage();
        }
    }

    /**
     * A bad {@code fontSizePx} is a PLAIN {@link IllegalArgumentException}, not a
     * {@link LatteXException} — the discrimination a modular consumer previously could
     * not make. Ordering matters: the narrower exported type is caught first, so
     * {@code "CAUGHT-IAE:"} can only be reached by a throwable that is NOT a
     * {@code LatteXException}.
     *
     * @return {@code "CAUGHT-LATTEX:"}, {@code "CAUGHT-IAE:"} or {@code "NO-THROW"}
     */
    public static String fragmentBadSize(double fontSizePx) {
        try {
            LatteX.renderFragment("x", fontSizePx);
            return "NO-THROW";
        } catch (LatteXException e) {
            return "CAUGHT-LATTEX:" + e.getMessage();
        } catch (IllegalArgumentException e) {
            return "CAUGHT-IAE:" + e.getMessage();
        }
    }
}
