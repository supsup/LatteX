// Modular-consumer compilation fixture — the POSITIVE half of the exported-throwable
// boundary regression (see ModularBoundaryTest).
//
// A REAL JPMS consumer: its own named module that `requires com.lattex` and, in
// Consumer.java, catches what the public render methods throw using ONLY packages the
// com.lattex module exports. If the exported exception supertype is present this
// compiles AND catches at runtime; before com.lattex.api.LatteXException existed it
// could not be written at all, because the only type the render methods threw lived in
// the non-exported com.lattex.parse package.
//
// The package is exported so the test can reflectively drive it from the unnamed module.
module lattexprobe {
    requires com.lattex;
    exports com.lattexprobe;
}
