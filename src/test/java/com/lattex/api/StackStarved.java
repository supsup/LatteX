package com.lattex.api;

import com.lattex.parse.MathSyntaxException;
import java.util.concurrent.Callable;

/**
 * Runs one call on a thread with a deliberately tiny REQUESTED stack, or on a roomy one, so a
 * test can tell the parser's two depth refusals apart and pin the channel a real
 * {@code StackOverflowError} arrives on.
 *
 * <p><strong>Why a starved stack.</strong> In production the host's render thread can already be
 * deep in its own frames (a doc pipeline, a template engine, a servlet filter chain) when it calls
 * LatteX. The parser's depth counter cannot see how much stack the caller has already spent, so a
 * deep input can exhaust the stack before the counter refuses it.
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
 * under test runs) must end in the BACKSTOP on the starved stack. A generic
 * {@code MathSyntaxException}, a GUARD refusal or an empty result earns no overflow credit,
 * because each of those also happens with no overflow at all.
 *
 * <p><strong>No portability claim.</strong> {@link #STACK_BYTES} and {@link #ROOMY_STACK_BYTES}
 * are REQUESTED sizes. The JVM may round a small request up to its platform minimum or ignore it,
 * and how many frames fit also depends on JIT state and the VM build. Whether {@link #DEEP}
 * overflows the starved stack is a measured property of the environments this suite has run in,
 * not a portable floor; the fixture-reach control is what turns a change in that property into a
 * loud failure instead of a vacuous pass.
 *
 * <p>Hazard note for anyone extending this: run a SHALLOW success through the same entry on the
 * roomy stack FIRST. A {@code StackOverflowError} inside a static initializer marks that class
 * permanently erroneous for the whole JVM, which would poison every later test; warming up on a
 * roomy stack initializes the classes before a starved run can interrupt them.
 */
final class StackStarved {

    /** Requested stack for the starved half; the JVM may round a small request up to its minimum. */
    static final long STACK_BYTES = 64L * 1024L;

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
     * On the roomy stack the GUARD refuses it. On the starved stack, in the measured environments,
     * the stack runs out inside the parser first and the BACKSTOP refuses it. That is measured,
     * not derived: the plan 28201def probe at 430a7fbf (results c8274640, lattex 1616) saw exactly
     * this split both interpreted and JIT-warmed. The same probe saw the deepest LEGAL input
     * (79 levels) parse fine on the starved stack once the JIT had compiled the parser, so a legal
     * input cannot carry the fixture-reach control in a warm suite JVM.
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

    /** Runs {@code call} on a starved-stack thread and reports what it returned or threw. */
    static Outcome run(Callable<?> call) {
        return runWithStack(call, STACK_BYTES, "lattex-stack-starved");
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
     * The fixture-reach control: on the STARVED stack the bare parse of {@link #DEEP} (the overload
     * the entry under test runs, called with no LatteX boundary around it) must end in the parser
     * BACKSTOP. If it does not, the seam test relying on this fixture would pass without ever
     * meeting an overflow, so this fails instead and says what changed.
     */
    static void assertStarvedParseHitsBackstop(String what, Callable<?> bareParse) {
        Outcome o = run(bareParse);
        if (!(o.threw() && isBackstop(o.thrown()))) {
            throw new AssertionError("fixture no longer reaches a real StackOverflowError: " + what
                + " of DEEP on a requested " + STACK_BYTES + "-byte stack must end in the parser BACKSTOP ("
                + BACKSTOP_MESSAGE + ", no cause) but " + o.describe()
                + ". A GUARD refusal or a success here means the parse fit the stack: shrink STACK_BYTES"
                + " or use a construct that costs more stack per level. A requested stack size is not a"
                + " portable floor.", o.thrown());
        }
    }
}
