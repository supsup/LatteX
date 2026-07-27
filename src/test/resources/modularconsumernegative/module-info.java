// Modular-consumer compilation fixture — the NEGATIVE half of the exported-throwable
// boundary regression (see ModularBoundaryTest).
//
// Identical in shape to the positive fixture, except NegativeConsumer.java names the
// NON-exported com.lattex.parse.MathSyntaxException. This module MUST FAIL to compile
// with a "package com.lattex.parse is not visible" diagnostic. If it ever starts
// compiling, the module fence has been silently widened and the test goes red.
module lattexprobenegative {
    requires com.lattex;
    exports com.lattexprobe;
}
