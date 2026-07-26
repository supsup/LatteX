package com.lattex.cli;

import com.lattex.api.Outcome;
import com.lattex.api.RenderResult;
import com.lattex.api.LatteX;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryIteratorException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Container-only folder worker. Every {@code .tex} job uses LatteX's public,
 * never-throwing diagnostic render API. This class owns only mounted-file
 * orchestration; it is not a second renderer or parser.
 *
 * <p>Claims are atomic renames to a UUID-prefixed direct child of
 * {@code input/processing}. The unique target means two workers can race for
 * one source without either overwriting the other. Completed output and source
 * archives are published with hard links from fully written files, which is an
 * atomic create-if-absent operation on the mounted filesystem. There is no
 * overwrite fallback.
 */
public final class LatteXFolderWorker {

    private static final Path DEFAULT_INPUT = Path.of("/lattex/input");
    private static final Path DEFAULT_OUTPUT = Path.of("/lattex/output");
    private static final long DEFAULT_POLL_MILLIS = 500;
    private static final long MIN_POLL_MILLIS = 10;
    private static final long MAX_POLL_MILLIS = 60_000;
    private static final int CLAIM_ID_LENGTH = 32;
    private static final String CLAIM_SEPARATOR = "--";
    private static final int DIAGNOSTIC_LIMIT = 512;
    // MathParser accepts at most 100,000 UTF-16 code units. Four bytes per
    // unit is a conservative read cap that also bounds malformed input before
    // decoding; the renderer remains the authoritative character-count gate.
    private static final int MAX_INPUT_BYTES = 400_000;

    private final Path input;
    private final Path output;
    private final Path processing;
    private final Path finished;
    private final Path failed;
    private final long pollMillis;

    private LatteXFolderWorker(Path input, Path output, long pollMillis) {
        this.input = input.toAbsolutePath().normalize();
        this.output = output.toAbsolutePath().normalize();
        this.processing = this.input.resolve("processing");
        this.finished = this.input.resolve("finished");
        this.failed = this.input.resolve("failed");
        this.pollMillis = pollMillis;
    }

    public static void main(String[] args) {
        if (args.length != 0) {
            System.err.println("lattex-watch: no arguments accepted");
            System.exit(2);
        }
        try {
            Path input = envPath("LATTEX_INPUT_DIR", DEFAULT_INPUT);
            Path output = envPath("LATTEX_OUTPUT_DIR", DEFAULT_OUTPUT);
            long pollMillis = envPollMillis();
            new LatteXFolderWorker(input, output, pollMillis).run();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (Exception failure) {
            // No exception message or path: mounted paths and host-specific
            // details may contain secrets. The class and local method name the
            // failure shape without exposing mounted content.
            System.err.println("lattex-watch: stopped after "
                + failure.getClass().getSimpleName() + " in "
                + localFailureSite(failure));
            System.exit(2);
        }
    }

    private static String localFailureSite(Throwable failure) {
        for (StackTraceElement frame : failure.getStackTrace()) {
            if (frame.getClassName().equals(LatteXFolderWorker.class.getName())) {
                return frame.getMethodName();
            }
        }
        return "worker";
    }

    private void run() throws IOException, InterruptedException {
        ensureDirectory(input);
        ensureDirectory(output);
        ensureDirectory(processing);
        ensureDirectory(finished);
        ensureDirectory(failed);
        ensureDirectory(finished.resolve("collisions"));
        ensureDirectory(failed.resolve("collisions"));

        System.err.println("lattex-watch: ready");
        while (!Thread.currentThread().isInterrupted()) {
            boolean worked = processRecoveredClaims();
            worked |= claimNewInputs();
            if (!worked) {
                Thread.sleep(pollMillis);
            }
        }
    }

    private boolean processRecoveredClaims() throws IOException {
        List<Claim> claims = new ArrayList<>();
        for (Path candidate : directChildren(processing)) {
            Claim claim = parseClaim(candidate);
            if (claim != null) {
                claims.add(claim);
            }
        }
        claims.sort(Comparator.comparing(claim -> claim.path().getFileName().toString()));
        boolean processedAny = false;
        for (Claim claim : claims) {
            processedAny |= tryProcess(claim);
        }
        return processedAny;
    }

    private boolean claimNewInputs() throws IOException {
        List<Path> candidates = new ArrayList<>();
        for (Path candidate : directChildren(input)) {
            if (eligible(candidate)) {
                candidates.add(candidate);
            }
        }
        candidates.sort(Comparator.comparing(path -> path.getFileName().toString()));

        boolean claimedAny = false;
        for (Path source : candidates) {
            String originalName = source.getFileName().toString();
            String jobId = UUID.randomUUID().toString().replace("-", "");
            Path claimed = processing.resolve(jobId + CLAIM_SEPARATOR + originalName);
            try {
                Files.move(source, claimed, StandardCopyOption.ATOMIC_MOVE);
                claimedAny = true;
                tryProcess(new Claim(jobId, originalName, claimed));
            } catch (NoSuchFileException | FileAlreadyExistsException raced) {
                // Another worker won the source rename, or an operator raced a
                // same-name state entry. No source bytes were overwritten.
            } catch (AtomicMoveNotSupportedException unsupported) {
                throw new IOException("input mount cannot atomically claim work", unsupported);
            }
        }
        return claimedAny;
    }

    /**
     * Prefer an exclusive lock on the claimed source. Some bind-mount drivers
     * do not coordinate advisory locks between containers, and a producer may
     * create a readable but non-writable file, so the processing and archive
     * paths are independently idempotent as the correctness boundary.
     */
    private boolean tryProcess(Claim claim) throws IOException {
        ClaimLease acquired;
        try {
            acquired = tryAcquire(claim.path());
        } catch (IOException unreadable) {
            return processUnreadable(claim);
        }
        try (ClaimLease lease = acquired) {
            if (lease == null) {
                return false;
            }
            String attemptId = UUID.randomUUID().toString().replace("-", "");
            process(claim, lease.channel(), attemptId);
            return true;
        }
    }

    /**
     * Directory write permission can be sufficient to archive an input whose
     * inode is not readable by the worker. Keep that job local and fail-honest
     * instead of stopping the long-running worker for every subsequent file.
     */
    private boolean processUnreadable(Claim claim) throws IOException {
        String attemptId = UUID.randomUUID().toString().replace("-", "");
        publishDiagnostic(claim, attemptId, "unreadable-input");
        if (archiveWithoutRead(claim, failed, attemptId)) {
            System.err.println("lattex-watch: job " + claim.jobId()
                + " failed (unreadable-input)");
            return true;
        }
        logAlreadyCompleted(claim);
        return false;
    }

    private static ClaimLease tryAcquire(Path claimed) throws IOException {
        if (!Files.isRegularFile(claimed, LinkOption.NOFOLLOW_LINKS)) {
            return null;
        }
        FileChannel channel;
        try {
            channel = FileChannel.open(claimed,
                StandardOpenOption.READ, StandardOpenOption.WRITE,
                LinkOption.NOFOLLOW_LINKS);
        } catch (AccessDeniedException readOnlySource) {
            return tryAcquireReadOnly(claimed);
        } catch (NoSuchFileException raced) {
            return null;
        }
        try {
            FileLock lock = channel.tryLock();
            if (lock == null) {
                channel.close();
                return null;
            }
            // A loser can open the inode while the winner still holds the
            // lock, then acquire that inode lock just after the winner links
            // it into finished/failed and deletes the processing pathname.
            // Revalidate the unique claim path after acquisition so the loser
            // never processes an already-archived inode through a vanished
            // path.
            if (!Files.isRegularFile(claimed, LinkOption.NOFOLLOW_LINKS)) {
                channel.close();
                return null;
            }
            return new ClaimLease(channel, lock);
        } catch (OverlappingFileLockException alreadyHeldHere) {
            channel.close();
            return null;
        } catch (IOException unsupportedLock) {
            channel.close();
            return tryAcquireReadOnly(claimed);
        } catch (RuntimeException failure) {
            channel.close();
            throw failure;
        }
    }

    private static ClaimLease tryAcquireReadOnly(Path claimed) throws IOException {
        FileChannel channel;
        try {
            channel = FileChannel.open(claimed,
                StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS);
        } catch (NoSuchFileException raced) {
            return null;
        }
        if (!Files.isRegularFile(claimed, LinkOption.NOFOLLOW_LINKS)) {
            channel.close();
            return null;
        }
        return new ClaimLease(channel, null);
    }

    private void process(Claim claim, FileChannel source, String attemptId)
            throws IOException {
        Path renderTemp = output.resolve(".lattex-watch-" + claim.jobId()
            + "-" + attemptId + ".svg.tmp");
        Files.deleteIfExists(renderTemp);

        try {
            render(claim, source, renderTemp);
            publishComplete(renderTemp, output.resolve(svgName(claim.originalName())));
        } catch (JobFailure failure) {
            Files.deleteIfExists(renderTemp);
            if (completedByAnotherWorker(claim, source)) {
                logAlreadyCompleted(claim);
                return;
            }
            if (fail(claim, source, attemptId, failure.code())) {
                System.err.println("lattex-watch: job " + claim.jobId()
                    + " failed (" + failure.code() + ")");
            } else {
                logAlreadyCompleted(claim);
            }
            return;
        } catch (IOException failure) {
            Files.deleteIfExists(renderTemp);
            if (completedByAnotherWorker(claim, source)) {
                logAlreadyCompleted(claim);
                return;
            }
            String code = "io-" + failure.getClass().getSimpleName();
            if (fail(claim, source, attemptId, code)) {
                System.err.println("lattex-watch: job " + claim.jobId()
                    + " failed (" + code + ")");
            } else {
                logAlreadyCompleted(claim);
            }
            return;
        } catch (RuntimeException failure) {
            Files.deleteIfExists(renderTemp);
            if (completedByAnotherWorker(claim, source)) {
                logAlreadyCompleted(claim);
                return;
            }
            String code = "runtime-" + failure.getClass().getSimpleName();
            if (fail(claim, source, attemptId, code)) {
                System.err.println("lattex-watch: job " + claim.jobId()
                    + " failed (" + code + ")");
            } else {
                logAlreadyCompleted(claim);
            }
            return;
        }

        if (archive(claim, source, finished)) {
            System.err.println("lattex-watch: job " + claim.jobId() + " finished");
        } else {
            logAlreadyCompleted(claim);
        }
    }

    private static void logAlreadyCompleted(Claim claim) {
        System.err.println("lattex-watch: job " + claim.jobId()
            + " already completed by another worker");
    }

    private boolean completedByAnotherWorker(Claim claim, FileChannel source)
            throws IOException {
        return !Files.exists(claim.path(), LinkOption.NOFOLLOW_LINKS)
            && archivedAnywhere(claim, source);
    }

    private void render(Claim claim, FileChannel source, Path renderTemp)
            throws IOException, JobFailure {
        byte[] bytes = readBounded(source, MAX_INPUT_BYTES);
        if (bytes.length > MAX_INPUT_BYTES) {
            throw new JobFailure("input-too-large");
        }
        String latex = decodeUtf8(bytes).strip();
        if (latex.isEmpty()) {
            throw new JobFailure("empty-input");
        }
        writeRenderedLatex(latex, renderTemp);
        if (!Files.isRegularFile(renderTemp, LinkOption.NOFOLLOW_LINKS)
                || Files.size(renderTemp) == 0) {
            throw new JobFailure("empty-output");
        }
    }

    private static byte[] readBounded(FileChannel source, int cap) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(Math.min(cap + 1, 8192));
        ByteBuffer buffer = ByteBuffer.allocate(8192);
        long position = 0;
        while (bytes.size() <= cap) {
            buffer.clear();
            buffer.limit(Math.min(buffer.capacity(), cap + 1 - bytes.size()));
            int read = source.read(buffer, position);
            if (read < 0) {
                break;
            }
            if (read == 0) {
                continue;
            }
            bytes.write(buffer.array(), 0, read);
            position += read;
        }
        return bytes.toByteArray();
    }

    private static String decodeUtf8(byte[] bytes) throws JobFailure {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString();
        } catch (CharacterCodingException invalid) {
            throw new JobFailure("invalid-utf8");
        }
    }

    private static void writeRenderedLatex(String latex, Path renderTemp)
            throws IOException, JobFailure {
        RenderResult result;
        try {
            result = LatteX.renderWithDiagnostics(latex);
        } catch (RuntimeException | StackOverflowError renderFailure) {
            throw new JobFailure("renderer-failure");
        }
        if (result == null || result.diagnostics() == null
                || result.diagnostics().outcome() != Outcome.OK
                || result.svg() == null) {
            String outcome = result == null || result.diagnostics() == null
                ? "unknown" : result.diagnostics().outcome().name().toLowerCase(Locale.ROOT);
            throw new JobFailure("render-" + outcome);
        }
        Files.writeString(renderTemp, result.svg(), StandardCharsets.UTF_8,
            StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
    }

    private boolean fail(Claim claim, FileChannel source, String attemptId,
            String rawCode) throws IOException {
        publishDiagnostic(claim, attemptId, rawCode);
        return archive(claim, source, failed);
    }

    private void publishDiagnostic(Claim claim, String attemptId, String rawCode)
            throws IOException {
        String code = boundedCode(rawCode);
        byte[] diagnostic = ("LatteX watch job failed: " + code + ".\n")
            .getBytes(StandardCharsets.UTF_8);
        Path temp = output.resolve(".lattex-watch-" + claim.jobId()
            + "-" + attemptId + ".error.tmp");
        Files.deleteIfExists(temp);
        Files.write(temp, diagnostic, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);

        Path direct = output.resolve(claim.originalName() + ".error.txt");
        try {
            publishComplete(temp, direct);
        } catch (JobFailure collision) {
            Files.deleteIfExists(temp);
            Files.write(temp, diagnostic, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            try {
                publishComplete(temp, output.resolve(
                    claim.originalName() + "." + claim.jobId() + ".error.txt"));
            } catch (JobFailure corruptRecovery) {
                throw new IOException("unique diagnostic path collision", corruptRecovery);
            }
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    /** Publish a fully written same-directory file without any overwrite path. */
    private static void publishComplete(Path complete, Path target)
            throws IOException, JobFailure {
        try {
            Files.createLink(target, complete);
        } catch (FileAlreadyExistsException collision) {
            if (!sameRegularFileBytes(complete, target)) {
                throw new JobFailure("output-collision");
            }
        }
        Files.delete(complete);
    }

    private boolean archive(Claim claim, FileChannel source, Path bucket)
            throws IOException {
        Path direct = bucket.resolve(claim.originalName());
        ArchiveAttempt directAttempt = tryArchiveAt(claim.path(), source, direct);
        if (directAttempt == ArchiveAttempt.ARCHIVED) {
            return true;
        }
        if (directAttempt == ArchiveAttempt.ALREADY_ARCHIVED) {
            return false;
        }

        Path collisionDir = bucket.resolve("collisions").resolve(claim.jobId());
        ensureDirectory(collisionDir);
        Path collisionTarget = collisionDir.resolve(claim.originalName());
        ArchiveAttempt collisionAttempt = tryArchiveAt(
            claim.path(), source, collisionTarget);
        if (collisionAttempt == ArchiveAttempt.ARCHIVED) {
            return true;
        }
        if (collisionAttempt == ArchiveAttempt.ALREADY_ARCHIVED
                || archivedAnywhere(claim, source)) {
            return false;
        }
        throw new IOException("claim source disappeared before archive");
    }

    private boolean archiveWithoutRead(Claim claim, Path bucket, String attemptId)
            throws IOException {
        Path direct = bucket.resolve(claim.originalName());
        try {
            Files.createLink(direct, claim.path());
            return Files.deleteIfExists(claim.path());
        } catch (FileAlreadyExistsException collision) {
            Path collisionDir = bucket.resolve("collisions")
                .resolve(claim.jobId() + "-" + attemptId);
            ensureDirectory(collisionDir);
            Path collisionTarget = collisionDir.resolve(claim.originalName());
            try {
                Files.createLink(collisionTarget, claim.path());
                return Files.deleteIfExists(claim.path());
            } catch (NoSuchFileException completedElsewhere) {
                return false;
            }
        } catch (NoSuchFileException completedElsewhere) {
            return false;
        }
    }

    private static ArchiveAttempt tryArchiveAt(Path sourcePath, FileChannel source,
            Path target)
            throws IOException {
        try {
            Files.createLink(target, sourcePath);
            return Files.deleteIfExists(sourcePath)
                ? ArchiveAttempt.ARCHIVED : ArchiveAttempt.ALREADY_ARCHIVED;
        } catch (FileAlreadyExistsException collision) {
            if (!sameOpenFileBytes(source, target)) {
                return ArchiveAttempt.TARGET_COLLISION;
            }
            return Files.deleteIfExists(sourcePath)
                ? ArchiveAttempt.ARCHIVED : ArchiveAttempt.ALREADY_ARCHIVED;
        } catch (NoSuchFileException sourceDisappeared) {
            return sameOpenFileBytes(source, target)
                ? ArchiveAttempt.ALREADY_ARCHIVED : ArchiveAttempt.SOURCE_MISSING;
        }
    }

    private boolean archivedAnywhere(Claim claim, FileChannel source) throws IOException {
        return archivedIn(finished, claim, source) || archivedIn(failed, claim, source);
    }

    private static boolean archivedIn(Path bucket, Claim claim, FileChannel source)
            throws IOException {
        Path direct = bucket.resolve(claim.originalName());
        Path collision = bucket.resolve("collisions").resolve(claim.jobId())
            .resolve(claim.originalName());
        return sameOpenFileBytes(source, direct) || sameOpenFileBytes(source, collision);
    }

    private static boolean sameOpenFileBytes(FileChannel source, Path target)
            throws IOException {
        if (!Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) {
            return false;
        }
        try (FileChannel other = FileChannel.open(target,
                StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
            long size = source.size();
            if (size != other.size()) {
                return false;
            }
            ByteBuffer left = ByteBuffer.allocate(8192);
            ByteBuffer right = ByteBuffer.allocate(8192);
            long position = 0;
            while (position < size) {
                int chunk = (int) Math.min(left.capacity(), size - position);
                left.clear();
                right.clear();
                left.limit(chunk);
                right.limit(chunk);
                if (readAt(source, left, position) != chunk
                        || readAt(other, right, position) != chunk) {
                    return false;
                }
                left.flip();
                right.flip();
                if (!left.equals(right)) {
                    return false;
                }
                position += chunk;
            }
            return true;
        } catch (NoSuchFileException raced) {
            return false;
        }
    }

    private static int readAt(FileChannel channel, ByteBuffer buffer, long position)
            throws IOException {
        int total = 0;
        while (buffer.hasRemaining()) {
            int read = channel.read(buffer, position + total);
            if (read <= 0) {
                break;
            }
            total += read;
        }
        return total;
    }

    private static boolean sameRegularFileBytes(Path first, Path second)
            throws IOException {
        return Files.isRegularFile(first, LinkOption.NOFOLLOW_LINKS)
            && Files.isRegularFile(second, LinkOption.NOFOLLOW_LINKS)
            && Files.mismatch(first, second) == -1;
    }

    private static List<Path> directChildren(Path dir) throws IOException {
        List<Path> children = new ArrayList<>();
        try (var stream = Files.newDirectoryStream(dir)) {
            for (Path child : stream) {
                children.add(child);
            }
        } catch (DirectoryIteratorException failure) {
            throw failure.getCause();
        }
        return children;
    }

    private static boolean eligible(Path path) {
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            return false;
        }
        String name = path.getFileName().toString();
        if (name.startsWith(".")) {
            return false;
        }
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.endsWith(".tex");
    }

    private static Claim parseClaim(Path path) {
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            return null;
        }
        String name = path.getFileName().toString();
        int prefixLength = CLAIM_ID_LENGTH + CLAIM_SEPARATOR.length();
        if (name.length() <= prefixLength
                || !name.regionMatches(CLAIM_ID_LENGTH, CLAIM_SEPARATOR, 0,
                    CLAIM_SEPARATOR.length())) {
            return null;
        }
        String jobId = name.substring(0, CLAIM_ID_LENGTH);
        for (int i = 0; i < jobId.length(); i++) {
            char c = jobId.charAt(i);
            if (!((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f'))) {
                return null;
            }
        }
        String original = name.substring(prefixLength);
        if (!eligibleName(original)) {
            return null;
        }
        return new Claim(jobId, original, path);
    }

    private static boolean eligibleName(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        return !name.startsWith(".") && lower.endsWith(".tex");
    }

    private static String svgName(String sourceName) {
        return sourceName.substring(0, sourceName.length() - ".tex".length()) + ".svg";
    }

    private static void ensureDirectory(Path dir) throws IOException {
        Files.createDirectories(dir);
        if (Files.isSymbolicLink(dir)
                || !Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("required worker path is not a real directory");
        }
    }

    private static Path envPath(String name, Path fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : Path.of(value);
    }

    private static long envPollMillis() {
        String value = System.getenv("LATTEX_WATCH_POLL_MS");
        if (value == null || value.isBlank()) {
            return DEFAULT_POLL_MILLIS;
        }
        try {
            long parsed = Long.parseLong(value.trim());
            if (parsed >= MIN_POLL_MILLIS && parsed <= MAX_POLL_MILLIS) {
                return parsed;
            }
        } catch (NumberFormatException ignored) {
            // Fixed refusal below; never echo the untrusted environment value.
        }
        throw new IllegalArgumentException(
            "LATTEX_WATCH_POLL_MS must be between 10 and 60000");
    }

    private static String boundedCode(String raw) {
        String code = raw == null ? "unknown" : raw.replaceAll("[^A-Za-z0-9._-]", "-");
        if (code.length() > DIAGNOSTIC_LIMIT) {
            return code.substring(0, DIAGNOSTIC_LIMIT);
        }
        return code;
    }

    private record Claim(String jobId, String originalName, Path path) { }

    private record ClaimLease(FileChannel channel, FileLock lock)
            implements AutoCloseable {
        @Override
        public void close() throws IOException {
            // Closing the channel releases any advisory lock. The worker's
            // idempotent state transitions remain the correctness boundary.
            channel.close();
        }
    }

    private enum ArchiveAttempt {
        ARCHIVED,
        ALREADY_ARCHIVED,
        TARGET_COLLISION,
        SOURCE_MISSING
    }

    private static final class JobFailure extends Exception {
        private static final long serialVersionUID = 1L;
        private final String code;

        JobFailure(String code) {
            super(code);
            this.code = code;
        }

        String code() {
            return code;
        }
    }
}
