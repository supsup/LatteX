package com.lattex.cli;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Covers the LTX-09 (plan ac28238e) streaming fix: a per-record read cap enforced
 * DURING the read (never {@code readAllBytes()} first), progressive record
 * production, byte-identical output for well-formed input, and split semantics
 * that exactly match the pre-change {@code String.split(delim, -1)} behavior.
 *
 * @see MainTest for the pre-existing small-input CLI behavior tests this is a
 *     memory/streaming-focused companion to.
 */
final class StreamingBatchTest {

    // ------------------------------------------------------------------
    // (1) Oversized record fails loud with a CONSTANT read-ahead bound
    //     (content stops at cap+1; read-ahead is one decode buffer, not the stream).
    // ------------------------------------------------------------------

    /**
     * An infinite supply of {@code 'a'} bytes that hard-fails if more than
     * {@code bound} bytes are ever requested — proof that a reader consuming it
     * keeps read-ahead CONSTANT-BOUNDED (one decode buffer past the cap) rather
     * than buffering the (would-be unbounded) stream.
     */
    private static final class BoundedInfiniteInputStream extends InputStream {
        private final long bound;
        private long count = 0;

        BoundedInfiniteInputStream(long bound) {
            this.bound = bound;
        }

        @Override
        public int read() throws IOException {
            if (count >= bound) {
                throw new IOException("read past the " + bound + "-byte bound — the reader"
                    + " kept reading instead of stopping at its per-record cap");
            }
            count++;
            return 'a';
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            if (count >= bound) {
                throw new IOException("read past the " + bound + "-byte bound — the reader"
                    + " kept reading instead of stopping at its per-record cap");
            }
            int n = (int) Math.min(len, bound - count);
            java.util.Arrays.fill(b, off, off + n, (byte) 'a');
            count += n;
            return n;
        }
    }

    @Test
    void oversizedSingleStdinExpressionFailsLoudWithConstantReadAheadBound() {
        // 5x MathParser.MAX_SOURCE_LENGTH of slack: generous vs. any reasonable chunking
        // overshoot, but nowhere near "buffer the whole (infinite) stream" — old
        // readAllBytes() code would read forever and eventually trip this bound.
        InputStream in = new BoundedInfiniteInputStream(5L * com.lattex.parse.MathParser.MAX_SOURCE_LENGTH);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int code = Main.run(new String[0], in,
            new PrintStream(out, true, StandardCharsets.UTF_8),
            new PrintStream(err, true, StandardCharsets.UTF_8));
        assertEquals(1, code, "an oversized stdin expression is a render/IO error, not a usage error");
        String errText = err.toString(StandardCharsets.UTF_8);
        assertTrue(errText.contains("exceeds the " + com.lattex.parse.MathParser.MAX_SOURCE_LENGTH + "-char limit"),
            () -> "expected the streaming cap message, got: " + errText);
        assertFalse(errText.contains("byte bound"),
            () -> "the bounded stream's own guard must never fire — that would mean the reader "
                + "kept reading well past the cap instead of stopping early; got: " + errText);
    }

    @Test
    void oversizedBatchRecordFailsLoudWithConstantBoundAndAbortsTheRestOfTheBatch() {
        // First a valid record, THEN an unbounded one — proves the valid record was
        // already streamed out before the reader ever got stuck on the bad one.
        InputStream prefix = new ByteArrayInputStream("x^2\n".getBytes(StandardCharsets.UTF_8));
        InputStream unbounded = new BoundedInfiniteInputStream(5L * com.lattex.parse.MathParser.MAX_SOURCE_LENGTH);
        InputStream in = new java.io.SequenceInputStream(prefix, unbounded);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int code = Main.run(new String[] {"--batch"}, in,
            new PrintStream(out, true, StandardCharsets.UTF_8),
            new PrintStream(err, true, StandardCharsets.UTF_8));
        assertEquals(1, code);
        String[] recs = out.toString(StandardCharsets.UTF_8).split("\0", -1);
        // First record rendered fine; second is the streaming-cap error; batch stops there.
        assertTrue(recs.length >= 2, () -> "expected at least 2 records, got " + recs.length);
        assertTrue(recs[0].startsWith("<svg"), "the sibling before the oversized record still rendered");
        assertTrue(recs[1].startsWith("lattex: error:") && recs[1].contains("exceeds the 100000-char limit"),
            () -> "expected a streaming-cap error record, got: " + recs[1]);
    }

    // ------------------------------------------------------------------
    // (1b) Tight unit-level bound on DelimitedRecordReader itself.
    // ------------------------------------------------------------------

    @Test
    void delimitedRecordReaderStopsWithinASmallConstantOfTheCap() throws Exception {
        int cap = 1000;
        // Only 4000 bytes of slack past the cap — if the reader buffered even one full
        // extra internal chunk beyond what's necessary it would still fit, but nothing
        // resembling "the whole stream" would.
        InputStream in = new BoundedInfiniteInputStream(cap + 4000);
        try (DelimitedRecordReader reader =
                 new DelimitedRecordReader(in, DelimitedRecordReader.NO_DELIMITER, cap)) {
            DelimitedRecordReader.TooLongException e =
                org.junit.jupiter.api.Assertions.assertThrows(
                    DelimitedRecordReader.TooLongException.class, reader::next);
            assertTrue(e.getMessage().contains("exceeds the " + cap + "-char limit"));
        }
    }

    // ------------------------------------------------------------------
    // (1c) The per-record cap is enforced on RAW length, BEFORE the caller's
    //      strip() — fail-closed on whitespace-padded transport (LTX-09).
    // ------------------------------------------------------------------

    /**
     * Pins the raw-before-strip cap policy at BOTH boundaries with surrounding
     * whitespace around one short, identical expression:
     *
     * <ul>
     *   <li>(a) a record of exactly {@code MAX_SOURCE_LENGTH} (100,000) RAW units whose
     *       non-whitespace payload is a short expression → SUCCEEDS (the reader accepts
     *       {@code maxChars}; {@code Main} then strips to the short expression and renders).</li>
     *   <li>(b) the SAME short expression padded to {@code MAX_SOURCE_LENGTH + 1} (100,001)
     *       RAW units → FAILS with the streaming-cap error BEFORE any trim. Both fixtures
     *       {@code strip()} to the byte-identical expression, so the ONLY thing that can
     *       distinguish them is the RAW length: (b) failing while (a) succeeds proves the
     *       cap is checked on the raw record, not on the post-strip content — the
     *       fail-closed policy that closes the whitespace-padding transport gap.</li>
     * </ul>
     */
    @Test
    void perRecordCapIsCheckedOnRawLengthBeforeStrip() {
        int cap = com.lattex.parse.MathParser.MAX_SOURCE_LENGTH; // 100,000
        String payload = "x^2";
        String atCap = whitespacePad(payload, cap);        // exactly 100,000 raw units
        String overCap = whitespacePad(payload, cap + 1);  // exactly 100,001 raw units
        assertEquals(cap, atCap.length());
        assertEquals(cap + 1, overCap.length());
        assertEquals(payload, atCap.strip(), "both fixtures must strip to the same short expression");
        assertEquals(payload, overCap.strip(), "both fixtures must strip to the same short expression");

        // (a) raw length == cap → accepted, stripped, rendered.
        ByteArrayOutputStream outA = new ByteArrayOutputStream();
        ByteArrayOutputStream errA = new ByteArrayOutputStream();
        int codeA = Main.run(new String[0],
            new ByteArrayInputStream(atCap.getBytes(StandardCharsets.UTF_8)),
            new PrintStream(outA, true, StandardCharsets.UTF_8),
            new PrintStream(errA, true, StandardCharsets.UTF_8));
        assertEquals(0, codeA, () -> "a 100,000-raw-unit record that strips to a short expression must "
            + "succeed; stderr: " + errA.toString(StandardCharsets.UTF_8));
        assertTrue(outA.toString(StandardCharsets.UTF_8).startsWith("<svg"),
            "the stripped short expression renders to an SVG");

        // (b) raw length == cap + 1 → rejected BEFORE the strip that would have shrunk it to 3 chars.
        ByteArrayOutputStream outB = new ByteArrayOutputStream();
        ByteArrayOutputStream errB = new ByteArrayOutputStream();
        int codeB = Main.run(new String[0],
            new ByteArrayInputStream(overCap.getBytes(StandardCharsets.UTF_8)),
            new PrintStream(outB, true, StandardCharsets.UTF_8),
            new PrintStream(errB, true, StandardCharsets.UTF_8));
        assertEquals(1, codeB, "a 100,001-raw-unit record fails even though it would strip to a short expression");
        String errText = errB.toString(StandardCharsets.UTF_8);
        assertTrue(errText.contains("exceeds the " + cap + "-char limit"),
            () -> "expected the streaming-cap error (cap hit on raw length, before strip), got: " + errText);
        assertTrue(outB.toString(StandardCharsets.UTF_8).isEmpty(),
            "an over-cap record produces no SVG output");
    }

    /** Builds a string of EXACTLY {@code total} chars: {@code payload} centered in ASCII spaces. */
    private static String whitespacePad(String payload, int total) {
        int pad = total - payload.length();
        int left = pad / 2;
        int right = pad - left;
        return " ".repeat(left) + payload + " ".repeat(right);
    }

    // ------------------------------------------------------------------
    // (2) Progressive production: each record is emitted before the whole
    //     input is consumed.
    // ------------------------------------------------------------------

    @Test
    void batchProducesEachRecordBeforeTheNextIsEvenRead() throws Exception {
        PipedOutputStream feed = new PipedOutputStream();
        PipedInputStream stdin = new PipedInputStream(feed, 256);
        SyncedCapture capture = new SyncedCapture();
        PrintStream out = new PrintStream(capture, true, StandardCharsets.UTF_8);
        PrintStream err = new PrintStream(OutputStream.nullOutputStream(), true, StandardCharsets.UTF_8);

        int[] exitCode = {-1};
        Thread worker = new Thread(() ->
            exitCode[0] = Main.run(new String[] {"--batch"}, stdin, out, err), "batch-worker");
        worker.setDaemon(true);
        worker.start();

        // Record 1, then wait for its record to land — while record 2/3 have not even
        // been sent yet, so the worker can only have produced this from record 1 alone.
        feed.write("x^2\n".getBytes(StandardCharsets.UTF_8));
        feed.flush();
        awaitRecordCount(capture, 1, Duration.ofSeconds(5));
        assertEquals(1, splitNulRecords(capture.snapshot()).size());
        assertTrue(splitNulRecords(capture.snapshot()).get(0).startsWith("<svg"));

        feed.write("\\frac{a}{b}\n".getBytes(StandardCharsets.UTF_8));
        feed.flush();
        awaitRecordCount(capture, 2, Duration.ofSeconds(5));

        feed.write("\\sqrt{2}\n".getBytes(StandardCharsets.UTF_8));
        feed.flush();
        awaitRecordCount(capture, 3, Duration.ofSeconds(5));

        // Now close the pipe (EOF) so the batch can finish; the trailing "" record from
        // -1-limit split semantics is blank and skipped, so still exactly 3 records.
        feed.close();
        worker.join(5000);
        assertFalse(worker.isAlive(), "worker must finish once stdin closes");
        assertEquals(0, exitCode[0]);
        List<String> recs = splitNulRecords(capture.snapshot());
        assertEquals(3, recs.size());
        for (String r : recs) {
            assertTrue(r.startsWith("<svg") && r.contains("</svg>"));
        }
    }

    /** Thread-safe (via its own monitor) byte sink the test thread can safely poll from another thread. */
    private static final class SyncedCapture extends OutputStream {
        private final ByteArrayOutputStream buf = new ByteArrayOutputStream();

        @Override
        public synchronized void write(int b) {
            buf.write(b);
        }

        @Override
        public synchronized void write(byte[] b, int off, int len) {
            buf.write(b, off, len);
        }

        synchronized byte[] snapshot() {
            return buf.toByteArray();
        }
    }

    private static List<String> splitNulRecords(byte[] bytes) {
        String s = new String(bytes, StandardCharsets.UTF_8);
        String[] parts = s.split("\0", -1);
        List<String> recs = new ArrayList<>();
        for (int i = 0; i < parts.length; i++) {
            if (i == parts.length - 1 && parts[i].isEmpty()) {
                continue; // trailing empty after the last NUL terminator
            }
            recs.add(parts[i]);
        }
        return recs;
    }

    private static void awaitRecordCount(SyncedCapture capture, int expected, Duration timeout) {
        Instant deadline = Instant.now().plus(timeout);
        while (Instant.now().isBefore(deadline)) {
            if (splitNulRecords(capture.snapshot()).size() >= expected) {
                return;
            }
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                fail("interrupted while waiting for progressive output");
            }
        }
        fail("timed out waiting for " + expected + " record(s); got: "
            + splitNulRecords(capture.snapshot()).size()
            + " — the batch is not streaming progressively");
    }

    // ------------------------------------------------------------------
    // (3) Byte-identity: representative multi-record batch output matches a
    //     golden captured from the pre-streaming-change implementation.
    // ------------------------------------------------------------------

    @Test
    void batchOutputIsByteIdenticalToThePreStreamingChangeGolden() throws Exception {
        String stdin = readResource("batch-golden.stdin.txt");
        byte[] golden = readResourceBytes("batch-golden.bin");

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        InputStream in = new ByteArrayInputStream(stdin.getBytes(StandardCharsets.UTF_8));
        Main.run(new String[] {"--batch"}, in,
            new PrintStream(out, true, StandardCharsets.UTF_8),
            new PrintStream(err, true, StandardCharsets.UTF_8));

        assertArrayEquals(golden, out.toByteArray(),
            "streamed batch output must be byte-identical to the pre-change golden for well-formed input");
    }

    private static String readResource(String name) throws IOException {
        return new String(readResourceBytes(name), StandardCharsets.UTF_8);
    }

    private static byte[] readResourceBytes(String name) throws IOException {
        try (InputStream in = StreamingBatchTest.class.getResourceAsStream("/com/lattex/cli/" + name)) {
            if (in == null) {
                throw new IOException("missing test resource: " + name);
            }
            return in.readAllBytes();
        }
    }

    // ------------------------------------------------------------------
    // (4) NUL/newline split semantics identical to String.split(delim, -1).
    // ------------------------------------------------------------------

    private static final String[] NEWLINE_FIXTURES = {
        "",                    // wholly empty input -> one empty record
        "abc",                 // single record, no delimiter at all
        "a\nb\nc",              // ordinary multi-record, no leading/trailing delimiter
        "a\nb\n",               // trailing delimiter -> trailing empty record
        "\na",                 // leading delimiter -> leading empty record
        "a\n\nb",               // empty record in the middle (consecutive delimiters)
        "\n",                  // delimiter alone -> two empty records
        "a\n\n",                // trailing delimiter pair -> two trailing empties
        "\\alpha\n\\beta\n\\gamma", // representative ASCII control-word content, no trailing delim
    };

    @Test
    void newlineDelimitedSplitMatchesStringSplitExactlyAcrossAMixedFixture() throws Exception {
        for (String fixture : NEWLINE_FIXTURES) {
            List<String> expected = List.of(fixture.split("\n", -1));
            List<String> actual = readAllRecords(fixture.getBytes(StandardCharsets.UTF_8), DelimitedRecordReader.NEWLINE);
            assertEquals(expected, actual, () -> "newline split mismatch for fixture: " + escape(fixture));
        }
    }

    @Test
    void nulDelimitedSplitMatchesStringSplitExactlyAcrossAMixedFixture() throws Exception {
        for (String newlineFixture : NEWLINE_FIXTURES) {
            // Reuse the same fixture shapes with NUL swapped in for '\n' (same structural cases:
            // trailing delimiter, empty record, single record, leading delimiter, consecutive delims).
            String fixture = newlineFixture.replace('\n', '\0');
            List<String> expected = List.of(fixture.split("\0", -1));
            List<String> actual = readAllRecords(fixture.getBytes(StandardCharsets.UTF_8), DelimitedRecordReader.NUL);
            assertEquals(expected, actual, () -> "NUL split mismatch for fixture: " + escape(fixture));
        }
    }

    @Test
    void splitStaysCorrectAcrossGenuineMultibyteContent() throws Exception {
        // The class javadoc's byte-vs-char delimiter-safety claim (0x0A / 0x00 never occur
        // inside a UTF-8 multi-byte sequence) deserves a GENUINELY multi-byte fixture — the
        // NEWLINE_FIXTURES above are ASCII control words. Exercise real 2-, 3-, and 4-byte
        // UTF-8 code points around the delimiters: é (U+00E9, 2 bytes), ∑ (U+2211, 3 bytes),
        // and 𝔸 (U+1D538, a surrogate pair / 4 UTF-8 bytes). None of their bytes is 0x0A/0x00.
        String bmp2 = "é";           // é
        String bmp3 = "∑";           // ∑
        String astral = "𝔸";   // 𝔸 (astral, surrogate pair)
        String fixture = bmp2 + "\n" + bmp3 + astral + "\n" + astral + bmp2;
        assertEquals(List.of(fixture.split("\n", -1)),
            readAllRecords(fixture.getBytes(StandardCharsets.UTF_8), DelimitedRecordReader.NEWLINE),
            "genuine BMP+astral content must split identically to String.split under newline");
        String nulFixture = fixture.replace('\n', '\0');
        assertEquals(List.of(nulFixture.split("\0", -1)),
            readAllRecords(nulFixture.getBytes(StandardCharsets.UTF_8), DelimitedRecordReader.NUL),
            "genuine BMP+astral content must split identically to String.split under NUL");
    }

    @Test
    void malformedUtf8DecodesToReplacementWithoutBreakingRecordBoundaries() throws Exception {
        // A lone lead byte (0xC3, expecting a continuation) immediately followed by a
        // newline: the newline (0x0A) is not a valid continuation, so the decoder replaces
        // the malformed byte with U+FFFD and STILL sees the newline as a record boundary —
        // the malformed byte neither swallows the delimiter nor triggers an unbounded read.
        // Asserted differentially against the same bytes decoded in bulk (both REPLACE).
        byte[] bytes = new byte[] {(byte) 0xC3, '\n', 'a', 'b'};
        String bulk = new String(bytes, StandardCharsets.UTF_8);
        List<String> expected = List.of(bulk.split("\n", -1));
        List<String> actual = readAllRecords(bytes, DelimitedRecordReader.NEWLINE);
        assertEquals(expected, actual,
            "malformed leading byte must decode to U+FFFD and keep the newline record boundary");
        assertEquals(2, actual.size(), "the newline must still split into exactly two records");
        assertEquals("ab", actual.get(1), "content after the malformed byte + delimiter is intact");
    }

    private static List<String> readAllRecords(byte[] bytes, int delimiter) throws IOException {
        List<String> recs = new ArrayList<>();
        try (DelimitedRecordReader reader =
                 new DelimitedRecordReader(new ByteArrayInputStream(bytes), delimiter, 1_000_000)) {
            String rec;
            while ((rec = reader.next()) != null) {
                recs.add(rec);
            }
        }
        return recs;
    }

    private static String escape(String s) {
        return s.replace("\n", "\\n").replace("\0", "\\0");
    }
}
