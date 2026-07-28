package com.example.api;

public enum MathStyle {
    DISPLAY,
    TEXT,
    SCRIPT,
    SCRIPT_SCRIPT;

    public MathStyle scriptStyle() {
        return switch (this) {
            case DISPLAY, TEXT -> SCRIPT;
            case SCRIPT, SCRIPT_SCRIPT -> SCRIPT_SCRIPT;
        };
    }

    public MathStyle fractionChildStyle() {
        return switch (this) {
            case DISPLAY -> TEXT;
            case TEXT -> SCRIPT;
            case SCRIPT, SCRIPT_SCRIPT -> SCRIPT_SCRIPT;
        };
    }

    public boolean isDisplay() {
        return this == DISPLAY;
    }
}
