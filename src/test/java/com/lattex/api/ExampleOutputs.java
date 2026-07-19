package com.lattex.api;

import java.nio.file.Path;

/**
 * Where the example-page GENERATORS write their HTML (plan 32148cc8 S2, reviewer F1).
 *
 * <p>The generators (the {@code @Tag("examples")} page tests) carry real
 * security / runtime / alphabet / safe-evaluator assertions, so they run in the
 * normal {@code test} suite — NOT excluded from it. To keep {@code test}
 * hermetic (the working tree stays clean), under {@code test} they write into
 * {@code build/examples}; only {@code generateExamples} — which sets
 * {@code -Dlattex.examples.write=true} — writes the tracked {@code examples/}
 * directory. This mirrors the BrewShot capture tests' {@code refsOut()} split.
 */
final class ExampleOutputs {

    private ExampleOutputs() {
    }

    /**
     * The tracked {@code examples/} dir when regenerating the committed artifacts
     * ({@code generateExamples} sets {@code lattex.examples.write=true}); otherwise
     * {@code build/examples}, so a normal {@code test} run never dirties the checkout.
     */
    static Path dir() {
        return Boolean.getBoolean("lattex.examples.write")
            ? Path.of("examples")
            : Path.of("build", "examples");
    }
}
