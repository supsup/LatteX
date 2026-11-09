package com.lattex.api;

import com.lattex.parse.MathSyntaxException;
import java.util.concurrent.Callable;

/**
 * Runs one call with almost no stack left, or on a roomy stack, so a test can tell the parser's
 * two depth refusals apart and pin the channel a real {@code StackOverflowError} arrives on.
 *
 * <p><strong>Why a starved stack.</strong> In production the host's render thread can already be
 * deep in its own frames (a doc pipeline, a template engine, a servlet filter chain) when it calls
 * LatteX. The parser's depth counter cannot see how much stack the caller has already spent, so a
 * deep input can exhaust the stack before the counter refuses it.
 *
 * <p><strong>How the stack is starved (plan 28201def, fixture rework).</strong> The starved call
 * is entered from an already deep call stack, the way the production hazard arises. The worker
 * thread recurses through a filler method until the stack really overflows, returns a chosen
 * number of filler frames back up (the RESERVE), and makes the call from there. What is left for
 * the call is therefore the reserve, whatever stack the platform gave the thread. A small
 * requested thread size alone is not enough: the JVM rounds a small request up to a platform
 * minimum, and on macOS on Apple silicon that minimum fits all {@value #PARSER_DEPTH_LIMIT}
 * levels, so the GUARD refused first and no overflow ever happened (lattex gate run at 2af4974).
 *
 * <p><strong>The reserve is searched, not assumed.</strong> How many bytes a filler frame or a
 * parser frame costs depends on the VM build and on what the JIT has compiled so far, so no fixed
 * reserve is right everywhere. {@link #assertStarvedParseHitsBackstop} walks a ladder of reserves
 * from small to large and classifies the bare parse at each rung: too little room and the
 * overflow escapes before the parser's own catch is reached; enough room for all
 * {@value #PARSER_DEPTH_LIMIT} levels and the GUARD refuses; in between is the BACKSTOP. It keeps
 * the middle rung of that BACKSTOP window, and {@link #run} then uses it. If no rung gives the
 * BACKSTOP the control fails and prints what every rung did.
 *
 * <p><strong>The two refusals (plan 28201def, nesting limit {@value #PARSER_DEPTH_LIMIT}).</strong>
 * <ul>
 *   <li>The GUARD: the depth counter refuses an input nested past the limit
 *       ({@link #GUARD_FRAGMENT}). On a roomy stack that is how {@link #DEEP} is refused, so a
 *       roomy deep call is a REFUSAL CONTROL, never a positive.</li>
 *   <li>The BACKSTOP: both public {@code MathParser.parse} entries catch a residual
 *       {@code StackOverflowError} and throw a NEW, cause-free {@code MathSyntaxException} with
 *       exactly {@link #BACKSTOP_MESSAGE}. Its only factory is called from those two catches, so
 *       that exact message is evidence that a real overflow fired inside the parser. The backstop
 *       does not keep the Error as a cause, and nothing here claims it does.</li>
 * </ul>
 *
 * <p><strong>Fixture reach, fail-loud.</strong> A caller proves the starved call really
 * overflowed with {@link #assertStarvedParseHitsBackstop}: the bare parse (the overload the entry
 * under test runs) must end in the BACKSTOP at some reserve. A generic
 * {@code MathSyntaxException}, a GUARD refusal or an empty result earns no overflow credit,
 * because each of those also happens with no overflow at all. {@link #run} refuses to run before
 * that control has passed in this JVM.
 *
 * <p><strong>What is and is not claimed.</strong> The kept reserve was measured a moment earlier
 * in the same JVM, on the same kind of thread, with the same parser; it is not derived. A seam
 * that answers empty gives no evidence of its own that it overflowed, so its credit rests on the
 * control that ran just before it and on the kept rung sitting in the middle of the window.
 *
 * <p>Hazard note for anyone extending this: run a SHALLOW success through the same entry on the
 * roomy stack FIRST. A {@code StackOverflowError} inside a static initializer marks that class
 * permanently erroneous for the whole JVM, which would poison every later test; warming up on a
 * roomy stack initializes the classes before a starved run can interrupt them.
 */
final class StackStarved {

    /**
     * Requested stack for the starved worker: above every platform minimum, so the worker is the
     * same nominal size everywhere, and large enough for the ladder to climb past the BACKSTOP
     * window. The room the call gets is set by the reserve, not by this.
     */
    static final long STACK_BYTES = 512L * 1024L;

    /** Smallest reserve tried, in filler frames. */
    static final int FIRST_RESERVE = 16;

    /** Largest reserve tried. Far more room than {@value #PARSER_DEPTH_LIMIT} levels need. */
    static final int LAST_RESERVE = 1 << 20;

    /** The reserve the last passing fixture-reach control kept; 0 until one has passed. */
    private static volatile int keptReserve;

    /** Requested stack for the roomy half: roomy, so even an all-interpreted run fits. */
    static final long ROOMY_STACK_BYTES = 256L * 1024L * 1024L;

    /**
     * The parser's nesting limit as these tests expect it. {@code MathParser.MAX_DEPTH} is
     * package-private to {@code com.lattex.parse}, so it cannot be read from here. This is not a
     * silent mirror: {@link #assertRoomyParseIsGuardRefusal} and
     * {@link #assertStarvedParseHitsBackstop} compare it against the live messages, so a changed
     * limit fails those controls loudly.
     */
    static final int PARSER_DEPTH_LIMIT = 80;

    /** The parser BACKSTOP's exact message: a caught residual StackOverflowError, no cause. */
    static final String BACKSTOP_MESSAGE = "nesting too deep: the parser's stack was exhausted before the "
        + PARSER_DEPTH_LIMIT + "-level limit; input refused";

    /** The depth GUARD's message core; each enclosing text-mode math span prefixes its context. */
    static final String GUARD_FRAGMENT = "nesting too deep: exceeds the " + PARSER_DEPTH_LIMIT + "-level limit";

    /**
     * The text-mode math construct nested {@code levels} deep: {@code \text} holding a {@code $}
     * span that holds the next level, with a single {@code y} innermost. Each level costs one
     * nesting level (the {@code \text} nucleus, whose {@code $} span is re-parsed with the outer
     * depth carried in) and the innermost {@code y} costs one more, so the input nests
     * {@code levels + 1} levels. Up to {@code PARSER_DEPTH_LIMIT - 1} levels is legal, and
     * {@code PARSER_DEPTH_LIMIT} levels trips the GUARD.
     *
     * <p>Why this construct: every level re-enters the parser through a long frame chain
     * (parseMath, parseTopLevel, parseComponent, parseNucleus, parseNucleusInner on the TEXT arm,
     * textWithNestedMath, and the parseMath overload chain back), roughly eleven frames per level,
     * several of them fat. Nested braces cost about four lean frames per level. The parse result
     * collapses to the single atom y, so layout and emit stay trivial.
     */
    static String nestedText(int levels) {
        return "\\text{$".repeat(levels) + "y" + "$}".repeat(levels);
    }

    /** Far under the limit: renders on every stack these tests use. The positive and the warm-up. */
    static final String SHALLOW = nestedText(3);

    /**
     * Far past the limit (500 levels against {@value #PARSER_DEPTH_LIMIT}), so it is NOT legal.
     * On the roomy stack the GUARD refuses it. With a small enough reserve the stack runs out
     * inside the parser before the counter reaches the limit and the BACKSTOP refuses it; the
     * fixture-reach control finds such a reserve or fails.
     */
    static final String DEEP = nestedText(500);

    /** What a call did: exactly one of {@code value} / {@code thrown} is meaningful. */
    record Outcome(Object value, Throwable thrown) {
        boolean threw() {
            return thrown != null;
        }

        /** Bounded: a GUARD refusal of DEEP carries a message hundreds of kilobytes long. */
        String describe() {
            String s = thrown == null ? "returned " + value
                : "threw " + thrown.getClass().getName()
                    + (thrown.getMessage() == null ? "" : ": " + thrown.getMessage())
                    + (thrown.getCause() == null ? "" : " (cause " + thrown.getCause().getClass().getName() + ")");
            return s.length() <= 400 ? s
                : s.substring(0, 200) + " ...[" + s.length() + " chars]... " + s.substring(s.length() - 160);
        }
    }

    private StackStarved() {
    }

    /**
     * Runs {@code call} with only the kept reserve of stack left and reports what it returned or
     * threw. Refuses unless {@link #assertStarvedParseHitsBackstop} has passed in this JVM, so a
     * starved run can never be made at a reserve nobody measured.
     */
    static Outcome run(Callable<?> call) {
        int reserve = keptReserve;
        if (reserve <= 0) {
            throw new AssertionError("StackStarved.run before any passing fixture-reach control:"
                + " call assertStarvedParseHitsBackstop first, so the reserve is a measured one");
        }
        Outcome o = runWithReserve(call, reserve);
        if (o == null) {
            throw new AssertionError("the starved worker's stack held fewer than the kept reserve of "
                + reserve + " filler frames, so the call was never made");
        }
        return o;
    }

    /**
     * Runs {@code call} on a worker that has first used up all but {@code reserve} filler frames.
     * Returns {@code null} when the worker's whole stack holds fewer than {@code reserve} filler
     * frames, in which case the call was never made.
     */
    private static Outcome runWithReserve(Callable<?> call, int reserve) {
        Object[] slot = new Object[2];
        boolean[] called = new boolean[1];
        int[] toUnwind = {reserve};
        Outcome worker = runWithStack(() -> {
            fill(call, toUnwind, called, slot);
            return null;
        }, STACK_BYTES, "lattex-stack-starved");
        if (worker.threw()) {
            throw new AssertionError("the filler descent itself failed at reserve " + reserve + ": "
                + worker.describe(), worker.thrown());
        }
        if (!called[0]) {
            return null;
        }
        return new Outcome(slot[0], (Throwable) slot[1]);
    }

    /**
     * Recurses until the stack overflows, then makes the call {@code toUnwind[0]} frames above the
     * deepest frame reached. Everything the call returns or throws is stored, never propagated,
     * so the {@code catch} below can only ever see the filler's own overflow and cannot swallow
     * an Error that escaped the entry under test.
     */
    private static void fill(Callable<?> call, int[] toUnwind, boolean[] called, Object[] slot) {
        try {
            fill(call, toUnwind, called, slot);
        } catch (StackOverflowError bottom) { // NOPMD - reaching the real bottom is the point
            // Fall through. A frame too deep to run even this handler overflows again and is
            // caught one frame up, which only shifts the count by a frame or two.
        }
        if (!called[0] && --toUnwind[0] <= 0) {
            called[0] = true;
            try {
                slot[0] = call.call();
            } catch (Throwable t) { // NOPMD - the point is to observe an escaping Error
                slot[1] = t;
            }
        }
    }

    /** Runs {@code call} on a roomy-stack thread and reports what it returned or threw. */
    static Outcome runRoomy(Callable<?> call) {
        return runWithStack(call, ROOMY_STACK_BYTES, "lattex-stack-roomy");
    }

    /**
     * The positive half: {@code call} must return normally on a ROOMY stack. Run off the test
     * thread so the result does not depend on the test runner's own stack size or on how much of
     * the parser the JIT has compiled yet. It also initializes every class the call touches, which
     * is the warm-up the starved run relies on. Feed it a SHALLOW input: a deep refusal is a
     * refusal control, not this positive.
     */
    static Object assertSucceedsOnRoomyStack(String what, Callable<?> call) {
        Outcome o = runRoomy(call);
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
                throw new AssertionError("interrupted waiting for the " + name + " call", e);
            }
            if (worker.isAlive()) {
                throw new AssertionError(name + " call did not finish within 60s");
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

    /** The parser BACKSTOP: exactly its message, its type, and no cause (a NEW exception). */
    static boolean isBackstop(Throwable t) {
        return t instanceof MathSyntaxException
            && BACKSTOP_MESSAGE.equals(t.getMessage())
            && t.getCause() == null;
    }

    /** The depth GUARD, and not the BACKSTOP. */
    static boolean isGuard(Throwable t) {
        return t instanceof MathSyntaxException
            && t.getMessage() != null
            && t.getMessage().contains(GUARD_FRAGMENT)
            && !t.getMessage().contains(BACKSTOP_MESSAGE);
    }

    /**
     * Real-overflow evidence at a public entry: either the parser BACKSTOP refused (a real
     * overflow fired inside the parse), or the entry's own {@code containRender} boundary
     * contained one (a typed failure whose cause chain holds the {@code StackOverflowError}). A
     * GUARD refusal, any other {@code MathSyntaxException}, or an empty result is not evidence,
     * because each of those also happens with no overflow.
     */
    static boolean isRealOverflowRefusal(Throwable t) {
        return isBackstop(t) || (t instanceof MathSyntaxException && overflowed(t.getCause()));
    }

    /**
     * The refusal control: on the ROOMY stack the bare parse of {@link #DEEP} is refused by the
     * GUARD, not the BACKSTOP. This is what makes a BACKSTOP on the starved stack attributable to
     * the starved stack.
     */
    static void assertRoomyParseIsGuardRefusal(String what, Callable<?> bareParse) {
        Outcome o = runRoomy(bareParse);
        if (!(o.threw() && isGuard(o.thrown()))) {
            throw new AssertionError("refusal control: " + what + " of DEEP on a roomy stack must be the depth"
                + " GUARD (" + GUARD_FRAGMENT + ") but " + o.describe(), o.thrown());
        }
    }

    /**
     * The fixture-reach control. Walks the reserve ladder with the bare parse of {@link #DEEP}
     * (the overload the entry under test runs, called with no LatteX boundary around it) and
     * requires at least one rung to end in the parser BACKSTOP, which only a real
     * {@code StackOverflowError} inside the parser produces. Keeps the middle rung of the
     * BACKSTOP window for {@link #run}. If no rung reaches the BACKSTOP, the seam test relying on
     * this fixture would pass without ever meeting an overflow, so this fails instead and prints
     * what every rung did.
     */
    static void assertStarvedParseHitsBackstop(String what, Callable<?> bareParse) {
        java.util.List<Integer> backstopRungs = new java.util.ArrayList<>();
        StringBuilder ladder = new StringBuilder();
        for (int reserve = FIRST_RESERVE; reserve <= LAST_RESERVE; reserve += Math.max(1, reserve / 2)) {
            Outcome o = runWithReserve(bareParse, reserve);
            String kind;
            if (o == null) {
                // The top of the ladder: the worker's whole stack is smaller than this reserve.
                ladder.append(reserve).append("=more than the worker's whole stack; ");
                break;
            }
            if (o.threw() && isBackstop(o.thrown())) {
                kind = "BACKSTOP";
                backstopRungs.add(reserve);
            } else if (o.threw() && isGuard(o.thrown())) {
                kind = "GUARD";
            } else if (o.threw() && o.thrown() instanceof StackOverflowError) {
                kind = "overflow escaped the bare parse";
            } else {
                kind = o.describe();
            }
            ladder.append(reserve).append('=').append(kind).append("; ");
            // Past the window: the parse fit the stack, and more room cannot bring the BACKSTOP back.
            if (!"BACKSTOP".equals(kind) && !(o.thrown() instanceof StackOverflowError)) {
                break;
            }
        }
        if (backstopRungs.isEmpty()) {
            throw new AssertionError("fixture no longer reaches a real StackOverflowError: " + what
                + " of DEEP must end in the parser BACKSTOP (" + BACKSTOP_MESSAGE + ", no cause) at some"
                + " reserve of stack, but no rung did. Reserve in filler frames = outcome: " + ladder
                + "A jump straight from an escaped overflow to the GUARD means the ladder steps over"
                + " the window: use a construct that costs more stack per level, or a finer ladder.");
        }
        int kept = backstopRungs.get(backstopRungs.size() / 2);
        keptReserve = kept;
        // Evidence for whoever reads the report: Gradle keeps this in the suite's system-out.
        System.out.println("StackStarved fixture reach, " + what + ": kept reserve " + kept
            + " of BACKSTOP rungs " + backstopRungs + ". Ladder: " + ladder);
    }
}
