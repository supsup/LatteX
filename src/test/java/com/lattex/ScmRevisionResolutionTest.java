package com.lattex;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/// Deterministic integration coverage for the production Gradle lineage provider.
///
/// Each fixture uses this checkout's real build.gradle.kts and invokes the narrow
/// verifyScmRevision task. Git-present fixtures are fresh repositories; Git-less fixtures contain
/// no .git entry at all, matching Docker's filtered build context.
class ScmRevisionResolutionTest {

    private static final Pattern RESOLVED =
        Pattern.compile("(?m)^LATTEX_SCM_REVISION=([0-9a-f]{40})$");

    @TempDir Path temporaryDirectory;

    @Test
    void gitPresentFallsBackToTheExactCheckoutHead() throws Exception {
        Path fixture = gitFixture("fallback");
        String head = gitHead(fixture);

        GradleResult result = runResolver(fixture, null);

        assertEquals(0, result.exitCode(), result.output());
        assertEquals(head, resolvedRevision(result));
    }

    @Test
    void gitPresentAcceptsTheExactConfiguredRevision() throws Exception {
        Path fixture = gitFixture("configured-exact");
        String head = gitHead(fixture);

        GradleResult result = runResolver(fixture, head);

        assertEquals(0, result.exitCode(), result.output());
        assertEquals(head, resolvedRevision(result));
    }

    @Test
    void malformedConfiguredRevisionFailsClosed() throws Exception {
        Path fixture = gitFixture("malformed");

        GradleResult result = runResolver(fixture, "ABC123");

        assertNotEquals(0, result.exitCode(), result.output());
        assertTrue(result.output().contains("expected one lowercase full 40-character Git SHA"),
            result.output());
    }

    @Test
    void configuredRevisionMustMatchGitCheckoutHead() throws Exception {
        Path fixture = gitFixture("mismatch");
        String head = gitHead(fixture);
        String wrong = head.equals("0".repeat(40)) ? "1".repeat(40) : "0".repeat(40);

        GradleResult result = runResolver(fixture, wrong);

        assertNotEquals(0, result.exitCode(), result.output());
        assertTrue(result.output().contains("does not match checkout HEAD"), result.output());
    }

    @Test
    void gitlessFixtureAcceptsAnExactConfiguredRevision() throws Exception {
        Path fixture = gitlessFixture("gitless-exact");
        String expected = "1234567890abcdef1234567890abcdef12345678";

        GradleResult result = runResolver(fixture, expected);

        assertEquals(0, result.exitCode(), result.output());
        assertEquals(expected, resolvedRevision(result));
    }

    @Test
    void gitlessFixtureWithoutConfiguredRevisionFailsClosed() throws Exception {
        Path fixture = gitlessFixture("gitless-absent");

        GradleResult result = runResolver(fixture, null);

        assertNotEquals(0, result.exitCode(), result.output());
        assertTrue(result.output().contains("set LATTEX_SOURCE_REVISION"), result.output());
    }

    private Path gitFixture(String name) throws Exception {
        Path fixture = gitlessFixture(name);
        assertCommandSuccess(fixture, List.of("git", "init", "--quiet"));
        assertCommandSuccess(fixture, List.of("git", "config", "user.email", "lattex-test@example.invalid"));
        assertCommandSuccess(fixture, List.of("git", "config", "user.name", "LatteX Test"));
        assertCommandSuccess(fixture, List.of("git", "add", "build.gradle.kts", "settings.gradle.kts"));
        assertCommandSuccess(fixture, List.of("git", "commit", "--quiet", "-m", "fixture"));
        return fixture;
    }

    private Path gitlessFixture(String name) throws IOException {
        Path fixture = temporaryDirectory.resolve(name);
        Files.createDirectories(fixture);
        Path projectRoot = Path.of("").toAbsolutePath();
        Files.copy(projectRoot.resolve("build.gradle.kts"), fixture.resolve("build.gradle.kts"),
            StandardCopyOption.REPLACE_EXISTING);
        Files.copy(projectRoot.resolve("settings.gradle.kts"), fixture.resolve("settings.gradle.kts"),
            StandardCopyOption.REPLACE_EXISTING);
        return fixture;
    }

    private static GradleResult runResolver(Path fixture, String configuredRevision)
            throws Exception {
        Path wrapper = Path.of("").toAbsolutePath().resolve("gradlew");
        List<String> command = List.of(
            wrapper.toString(), "-p", fixture.toString(), "verifyScmRevision",
            "--no-daemon", "--no-watch-fs", "--console=plain", "--stacktrace");
        ProcessBuilder builder = new ProcessBuilder(command)
            .directory(fixture.toFile())
            .redirectErrorStream(true);
        Map<String, String> environment = builder.environment();
        if (configuredRevision == null) {
            environment.remove("LATTEX_SOURCE_REVISION");
        } else {
            environment.put("LATTEX_SOURCE_REVISION", configuredRevision);
        }
        Process process = builder.start();
        if (!process.waitFor(90, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new AssertionError("Gradle revision resolver did not finish within 90 seconds");
        }
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        return new GradleResult(process.exitValue(), output);
    }

    private static String resolvedRevision(GradleResult result) {
        Matcher matcher = RESOLVED.matcher(result.output());
        assertTrue(matcher.find(), result.output());
        return matcher.group(1);
    }

    private static String gitHead(Path fixture) throws Exception {
        CommandResult result = command(fixture, List.of("git", "rev-parse", "HEAD"));
        assertEquals(0, result.exitCode(), result.output());
        return result.output().trim();
    }

    private static void assertCommandSuccess(Path directory, List<String> command)
            throws Exception {
        CommandResult result = command(directory, command);
        assertEquals(0, result.exitCode(), result.output());
    }

    private static CommandResult command(Path directory, List<String> command) throws Exception {
        Process process = new ProcessBuilder(new ArrayList<>(command))
            .directory(directory.toFile())
            .redirectErrorStream(true)
            .start();
        if (!process.waitFor(30, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new AssertionError("Command timed out: " + command);
        }
        return new CommandResult(process.exitValue(),
            new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
    }

    private record GradleResult(int exitCode, String output) {}
    private record CommandResult(int exitCode, String output) {}
}
