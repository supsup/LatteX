package com.example.layout;

import com.example.api.MathStyle;

final class LayoutContext {
    private final MathStyle style;

    LayoutContext(MathStyle style) {
        this.style = style;
    }

    private static MathStyle scriptStyle(MathStyle style) {
        return switch (style) {
            case DISPLAY, TEXT -> MathStyle.SCRIPT;
            case SCRIPT, SCRIPT_SCRIPT -> MathStyle.SCRIPT_SCRIPT;
        };
    }

    private static MathStyle fractionChildStyle(MathStyle style) {
        return switch (style) {
            case DISPLAY -> MathStyle.TEXT;
            case TEXT -> MathStyle.SCRIPT;
            case SCRIPT, SCRIPT_SCRIPT -> MathStyle.SCRIPT_SCRIPT;
        };
    }

    private boolean isDisplay() {
        return style == MathStyle.DISPLAY;
    }
}
