package com.example.layout;

public enum MathStyle {
    DISPLAY,
    TEXT,
    SCRIPT,
    SCRIPT_SCRIPT;

    MathStyle scriptStyle() {
        return switch (this) {
            case DISPLAY, TEXT -> SCRIPT;
            case SCRIPT, SCRIPT_SCRIPT -> SCRIPT_SCRIPT;
        };
    }

    MathStyle fractionChildStyle() {
        return switch (this) {
            case DISPLAY -> TEXT;
            case TEXT -> SCRIPT;
            case SCRIPT, SCRIPT_SCRIPT -> SCRIPT_SCRIPT;
        };
    }

    boolean isDisplay() {
        return this == DISPLAY;
    }
}
