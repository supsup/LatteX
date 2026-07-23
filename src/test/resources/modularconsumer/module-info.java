// Modular-consumer compilation fixture (plan 0c4f6015, Marlow audit LTX-08).
//
// A REAL JPMS consumer: its own named module that `requires com.lattex` and, in
// Consumer.java, catches the exception the public render methods throw and selects a
// math style — using ONLY packages the com.lattex module exports. If the exported API
// boundary is self-sufficient this compiles; if it leaks a type from a non-exported
// package (the LTX-08 defects), this consumer fails to compile. That failure IS the
// regression this fixture guards against — see ModularBoundaryTest.
module lattexprobe {
    requires com.lattex;
}
