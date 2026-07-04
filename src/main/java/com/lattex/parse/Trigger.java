package com.lattex.parse;

/**
 * The interaction that fires an {@code \lx} animation effect — the key half of an
 * {@code fx.*} option ({@code fx.enter} / {@code fx.hover} / {@code fx.click}).
 */
public enum Trigger {
    /** The formula entering the viewport / being revealed ({@code fx.enter}). */
    ENTER,
    /** Pointer hover over the formula ({@code fx.hover}). */
    HOVER,
    /** A click/tap on the formula ({@code fx.click}). */
    CLICK
}
