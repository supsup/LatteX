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
 * <p>Hazard note for anyone extending this: run the same call through
 * {@link #assertSucceedsOnRoomyStack} FIRST. A {@code StackOverflowError} inside a static
 * initializer marks that class permanently erroneous for the whole JVM, which would poison
 * every later test; warming up on a roomy stack initializes the classes before the starved
 * run can interrupt them.
 */
final class StackStarved {

    /** Requested stack; the JVM rounds a request below its platform minimum up to the minimum. */
    static final long STACK_BYTES = 64L * 1024L;

    /** Requested stack for the positive half: roomy, so even an all-interpreted first run fits. */
    static final long ROOMY_STACK_BYTES = 256L * 1024L * 1024L;

    /**
     * {@code \text{$\text{$ ... y ... $}$}} nested 500 deep: one {@code MAX_DEPTH} level per
     * nesting (the {@code parseNucleus} for each {@code \text}), so 501 levels stay under the
     * parser's 512-level limit and the input is legal (TextNestedMathTest pins that 600 levels
     * trip the guard, i.e. the parse reaches level 512 on an ordinary stack).
     *
     * <p>Why this construct and not nested braces (round 3): every level re-enters the parser
     * through a long frame chain, parseMath(4-arg) then parseTopLevel, parseComponent,
     * parseNucleus, parseNucleusInner (TEXT arm), textWithNestedMath twice, and the
     * parseMath(span, depth) overload chain back to parseMath(4-arg), roughly eleven frames per
     * level, several of them fat (try/catch, many locals). Nested braces cost about four lean
     * frames per level (parseComponent, parseNucleus, parseNucleusInner, parseGroup). 500
     * levels of braces DID overflow a minimum-size stack while the parser was still interpreted,
     * and stopped overflowing once the JIT had compiled it: the fixture-reach control caught
     * exactly that in the first gradle run. This construct is chosen for a margin of several
     * times that, compiled or not. The parse result collapses to the single atom y (a one-item
     * run returns its item), so layout and emit stay trivial and the overflow is in the parse.
     */
    static final String DEEP = "\\text{$".repeat(500) + "y" + "$}".repeat(500);

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
        return runWithStack(call, STACK_BYTES, "lattex-stack-starved");
    }

    /**
     * The positive half: {@code call} must return normally on a ROOMY stack. Run off the test
     * thread so the result does not depend on the test runner's own stack size or on how much
     * of the parser the JIT has compiled yet. It also initializes every class the call touches,
     * which is the warm-up the starved run relies on.
     */
    static Object assertSucceedsOnRoomyStack(String what, Callable<?> call) {
        Outcome o = runWithStack(call, ROOMY_STACK_BYTES, "lattex-stack-roomy");
        if (o.threw()) {
            throw new AssertionError(what + " must succeed on a roomy stack but " + o.describe(), o.thrown());
        }
        return o.value();
    }

    private static Outcome runWithStack(Callable<?> call, long stackBytes, String name) {
        Object[] value = new Object[1];
        Throwable[] thrown = new Throwable[1];
        Thread worker = new Thread(null, () -> {
            try {
                value[0] = call.call();
            } catch (Throwable t) { // NOPMD - the point is to observe an escaping Error
                thrown[0] = t;
            }
        }, name, stackBytes);
        worker.start();
        try {
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
        } finally {
            // No worker outlives the call: on timeout or interrupt, interrupt it and join it
            // (bounded). A no-op on the success path, where the worker has already ended. The
            // interrupt is cooperative (never Thread.stop), so a worker that ignores it is
            // reported rather than silently leaked.
            if (worker.isAlive()) {
                worker.interrupt();
                boolean wasInterrupted = Thread.interrupted();
                try {
                    worker.join(5_000L);
                } catch (InterruptedException e) {
                    wasInterrupted = true;
                } finally {
                    if (wasInterrupted) {
                        Thread.currentThread().interrupt();
                    }
                }
                if (worker.isAlive()) {
                    System.err.println("StackStarved: worker " + name + " ignored interrupt and is still alive");
                }
            }
        }
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
