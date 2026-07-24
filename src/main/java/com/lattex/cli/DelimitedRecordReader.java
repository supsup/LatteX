package com.lattex.cli;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * Streams delimiter-separated records out of an {@link InputStream} one at a time,
 * decoding UTF-8 incrementally and enforcing a per-record {@code char} cap DURING
 * the read (Marlow audit LTX-09, plan ac28238e).
 *
 * <p><strong>Why this exists.</strong> {@link com.lattex.parse.MathParser} already
 * rejects a source longer than {@link com.lattex.parse.MathParser#MAX_SOURCE_LENGTH}
 * -- but only <em>after</em> the CLI had already read the whole record into memory
 * (single-expression stdin: {@code readAllBytes()}; {@code --batch}: {@code
 * readAllBytes()} then a regex split of the WHOLE input before the first result is
 * even produced). The parser's cap is a per-record content guard, not a transport
 * guard: a multi-gigabyte stream never puts any single record over the cap on its
 * own, so the aggregate {@code readAllBytes()} call exhausts process memory before
 * the parser ever runs. This reader closes that gap by capping accumulated
 * characters as they are decoded, and throwing the moment the cap is crossed.
 *
 * <p><strong>The resource bound (precise -- it is CONSTANT, not zero-read-ahead).</strong>
 * Two distinct quantities are bounded, and conflating them is a truthfulness trap:
 * <ul>
 *   <li><em>Accumulated record CONTENT</em> -- the {@link StringBuilder} {@link #next()}
 *       builds -- never grows past {@code maxChars + 1} UTF-16 units: the append that
 *       reaches {@code maxChars + 1} is the one that throws, so the retained content is
 *       exactly {@code maxChars + 1} chars at worst.</li>
 *   <li><em>Decoder read-ahead</em> -- the bytes the {@link InputStreamReader} has
 *       already pulled from the underlying stream to decode those chars -- is BOUNDED
 *       but NOT zero, and can exceed {@code maxChars} bytes. A single {@code read}
 *       fills up to {@link #CHUNK_CHARS} chars, and the reader's own internal decode
 *       buffer reads a bounded amount further ahead to produce them. So at the instant
 *       of the throw the process may hold on the order of {@code maxChars} + one chunk
 *       + one decoder buffer worth of bytes (measured: ~106,496 bytes were pulled to
 *       decode char 100,001 at a 100,000-char cap). That total is a small constant
 *       past the cap, independent of how large the surrounding stream is -- never
 *       O(stream). The bound is safely CONSTANT; the specific bytes pulled are not zero.</li>
 * </ul>
 * Once the cap is crossed and {@link TooLongException} is thrown, NO ADDITIONAL read is
 * issued to the underlying stream. The earlier "no further bytes were buffered" phrasing
 * is deliberately not used: bytes were read ahead (bounded); the true guarantee is that
 * the read-ahead is a bounded constant past the cap and that reading stops on the throw.
 *
 * <p><strong>The cap is on RAW decoded length, before any caller-side trimming.</strong>
 * A record's length is checked against {@code maxChars} as it is decoded, with the
 * delimiter stripped but no other transformation applied. Callers that {@code strip()}
 * (or otherwise shrink) a record AFTER {@link #next()} returns get fail-closed behavior:
 * a record whose RAW length crosses the cap is rejected here BEFORE the caller ever sees
 * it, even if it would have trimmed down to a short expression. This closes the
 * whitespace-padding transport gap -- {@code maxChars} whitespace-padded units cannot be
 * used to smuggle past the read-time cap on the theory that a later {@code strip()} makes
 * the content short. See {@code Main.run}, which strips only after this reader returns.
 *
 * <p><strong>Split semantics.</strong> Mirrors {@code String.split(delimiter, -1)}
 * exactly: every delimiter occurrence ends a record (including back-to-back
 * delimiters, which yield an empty record), and there is always exactly one more
 * record after the last delimiter -- possibly empty, including when the stream is
 * empty or ends exactly on a delimiter. {@link #next()} returns {@code null} only
 * once that final record has already been handed back, matching the {@code -1}
 * limit's "keep trailing empties" behavior that {@code Main} relied on before
 * ({@code input.split(delim, -1)}; blank records are filtered by the caller, same
 * as before).
 *
 * <p><strong>Byte-vs-char delimiter matching is safe here.</strong> Both delimiters
 * this reader is ever configured with -- newline (0x0A) and NUL (0x00) -- are
 * single-byte ASCII code points. In UTF-8 those byte values never occur as part
 * of a multi-byte sequence (continuation bytes are 0x80-0xBF, leading bytes
 * 0xC0-0xFF), so decoding first and matching the decoded {@code char} against the
 * delimiter (what this class does, via {@link InputStreamReader}) produces
 * identical splits to matching the same byte in the raw stream.
 *
 * <p><strong>Not thread-safe; not reusable</strong> after {@link #close()}.
 */
final class DelimitedRecordReader implements AutoCloseable {

    /** Sentinel: no delimiter -- {@link #next()} returns the whole stream as one record. */
    static final int NO_DELIMITER = -1;

    /** NUL delimiter code point, for {@code --batch --null}. */
    static final int NUL = 0;

    /** Newline delimiter code point, for default {@code --batch}. */
    static final int NEWLINE = '\n';

    /** Internal decode chunk size, in chars -- bounds how far a single {@code read} call can overshoot the cap. */
    private static final int CHUNK_CHARS = 4096;

    /** Thrown when a record's decoded length exceeds the configured cap. Reading of THAT record stops immediately. */
    static final class TooLongException extends IOException {
        TooLongException(String message) {
            super(message);
        }
    }

    private final InputStreamReader reader;
    private final int delimiter;
    private final int maxChars;
    private final char[] buf = new char[CHUNK_CHARS];
    private int bufLen = 0;
    private int bufPos = 0;
    private boolean streamEof = false;
    /** Once true, the terminal (possibly empty) record has already been returned; no more records exist. */
    private boolean exhausted = false;

    /**
     * @param in        the byte stream to decode as UTF-8
     * @param delimiter the delimiter code point ({@link #NEWLINE} or {@link #NUL}),
     *                  or {@link #NO_DELIMITER} to treat the whole stream as a single record
     * @param maxChars  the per-record cap, in decoded {@code char}s (inclusive -- exactly
     *                  {@code maxChars} chars is accepted, {@code maxChars + 1} is not,
     *                  matching {@code MathParser}'s {@code length() > MAX_SOURCE_LENGTH} check)
     */
    DelimitedRecordReader(InputStream in, int delimiter, int maxChars) {
        this.reader = new InputStreamReader(in, StandardCharsets.UTF_8);
        this.delimiter = delimiter;
        this.maxChars = maxChars;
    }

    /**
     * Returns the next record (decoded, delimiter stripped, cap-checked), or
     * {@code null} once every record -- including the final possibly-empty one --
     * has already been returned.
     *
     * @throws TooLongException if the current record's decoded length exceeds
     *                          {@code maxChars} before a delimiter or EOF is reached.
     *                          No ADDITIONAL read is issued to the underlying stream
     *                          once this is thrown; the bounded read-ahead already
     *                          pulled to decode the offending char (a small constant
     *                          past the cap, not zero -- see the class javadoc) stays
     *                          in the decoder's buffer and is discarded on {@link #close()}.
     *                          The caller should treat the rest of the stream as
     *                          unrecoverable: its record boundaries cannot be located
     *                          without an unbounded read.
     * @throws IOException      on an underlying I/O failure
     */
    String next() throws IOException {
        if (exhausted) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        while (true) {
            if (bufPos >= bufLen) {
                if (streamEof) {
                    exhausted = true;
                    return sb.toString();
                }
                bufLen = reader.read(buf);
                bufPos = 0;
                if (bufLen < 0) {
                    streamEof = true;
                    exhausted = true;
                    return sb.toString();
                }
                if (bufLen == 0) {
                    continue;
                }
            }
            char c = buf[bufPos++];
            if (delimiter != NO_DELIMITER && c == delimiter) {
                return sb.toString();
            }
            sb.append(c);
            if (sb.length() > maxChars) {
                throw new TooLongException(
                    "record exceeds the " + maxChars + "-char limit -- stopped reading"
                        + " this record (streaming cap, LTX-09); no further reads are"
                        + " issued, and read-ahead was bounded to one decode buffer past"
                        + " the cap, not the rest of the stream");
            }
        }
    }

    @Override
    public void close() throws IOException {
        reader.close();
    }
}
