package com.lattexprobe;

import com.lattex.api.LatteX;
import com.lattex.api.LatteXException;
import com.lattex.api.MathStyle;
import com.lattex.api.RenderOptions;

/**
 * A modular consumer of LatteX that names ONLY exported {@code com.lattex.api} types:
 * {@link LatteX}, {@link RenderOptions}, {@link MathStyle}, and {@link LatteXException}.
 *
 * <p>Pre-LTX-08 this class could NOT be written against the exported surface: the only
 * exception the render methods throw lived in the non-exported {@code com.lattex.parse}
 * package, and {@code RenderOptions} named the non-exported {@code com.lattex.layout}
 * style enum in its public signature. This fixture proves both holes are closed.
 */
public final class Consumer {

    private Consumer() {
    }

    /** Renders {@code latex}, catching the public exception via an exported supertype. */
    public static String render(String latex) {
        // Every one of these selectors is reachable without naming a non-exported type.
        RenderOptions opts = RenderOptions.defaults()
            .display()
            .inline()
            .script()
            .scriptScript()
            .withMathStyle(MathStyle.TEXT);   // the exported style enum, named directly
        try {
            return LatteX.render(latex, opts);
        } catch (LatteXException e) {          // the exported exception supertype
            // A modular consumer can catch what the render methods throw using only
            // exported packages — the whole point of the fix.
            return e.getMessage();
        }
    }
}
