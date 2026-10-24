package com.lattex.api;

import java.util.concurrent.Callable;

/**
 * Runs one call on a thread whose stack is deliberately tiny, so a LEGAL input that renders
 * fine on a normal stack overflows inside the call. This is how a real
 * {@code StackOverflowError} reaches a public entry point in production: the host's render
 * thread is already deep in its own frames (a doc pipeline, a template engine, a servlet
 * filter chain) when it calls LatteX, and the parser's {@code MAX_DEPTH} guard cannot see
 * how much stack the caller has already spent.
 *
 * <p>Plan lattex-seam-containment-parity. The fixture is FAIL-LOUD rather than vacuous: a
 * caller pairs every starved call with {@link #assertFixtureOverflows}, which proves the
 * same input really does overflow on this stack. Without that control a green result could
 * mean only that the input stopped being deep enough to reach an overflow.
 *
 * <p>Hazard note for anyone extending this: run the same call on the normal test thread
 * FIRST. A {@code StackOverflowError} inside a static initializer marks that class
 * permanently erroneous for the whole JVM, which would poison every later test; warming up
 * on a full stack initializes the classes before the starved run can interrupt them.
 */
final class StackStarved {

    /** Requested stack; the JVM rounds a request below its platform minimum up to the minimum. */
    static final long STACK_BYTES = 64L * 1024L;

    /**
     * Nested braces 500 deep: just under the parser's 512-level {@code MAX_DEPTH}, so the
     * input is legal and renders on a normal stack (the positive half of the fixture), but
     * the parser's recursion is deep enough to exhaust a minimum-size thread stack.
     */
    static final String DEEP = "{".repeat(500) + "x" + "}".repeat(500);

    /** What the starved call did: exactly one of {@code value} / {@code thrown} is meaningful. */
    record Outcome(Object value, Throwable thrown) {
        boolean threw() {
            return thrown != null;
        }

        String describe() {
            return thrown == null ? "returned " + value
                : "threw " + thrown.getClass().getName()
                    + (thrown.getMessage() == null ? "" : ": " + thrown.getMessage());
        }
    }

    private StackStarved() {
    }

    /** Runs {@code call} on a starved-stack thread and reports what it returned or threw. */
    static Outcome run(Callable<?> call) {
        Object[] value = new Object[1];
        Throwable[] thrown = new Throwable[1];
        Thread worker = new Thread(null, () -> {
            try {
                value[0] = call.call();
            } catch (Throwable t) { // NOPMD - the point is to observe an escaping Error
                thrown[0] = t;
            }
        }, "lattex-stack-starved", STACK_BYTES);
        worker.start();
        try {
            worker.join(60_000L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted waiting for the starved-stack call", e);
        }
        if (worker.isAlive()) {
            throw new AssertionError("starved-stack call did not finish within 60s");
        }
        return new Outcome(value[0], thrown[0]);
    }

    /** Whether {@code t} is, or is caused by, a {@code StackOverflowError}. */
    static boolean overflowed(Throwable t) {
        int hops = 0;
        for (Throwable c = t; c != null && hops < 32; c = c.getCause(), hops++) {
            if (c instanceof StackOverflowError) {
                return true;
            }
        }
        return false;
    }

    /**
     * The fixture-reach control: {@code probe} (the first stage the seam under test runs, called
     * bare with no guard around it) must actually overflow on the starved stack. If it does not,
     * the seam test that relies on this fixture would pass without ever meeting an Error, so this
     * fails instead and says how to restore the fixture.
     */
    static void assertFixtureOverflows(String what, Callable<?> probe) {
        Outcome o = run(probe);
        if (!(o.threw() && overflowed(o.thrown()))) {
            throw new AssertionError("fixture no longer reaches a StackOverflowError: " + what
                + " on a " + STACK_BYTES + "-byte stack " + o.describe()
                + " -- deepen DEEP (still under MathParser.MAX_DEPTH) or shrink STACK_BYTES");
        }
    }
}
