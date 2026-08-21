package com.lattex;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarFile;
import java.util.jar.Manifest;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/// A version label is not source identity: the real jar must name the exact checkout HEAD.
///
/// The test task supplies the jar through `lattex.moduleJar`, which is also a declared task
/// input. Reading that artifact rather than an exploded test classpath keeps this guard attached
/// to the bytes a downstream consumer actually receives.
class ArtifactLineageGuardTest {

    private static final String REVISION_ATTRIBUTE = "Implementation-SCM-Revision";
    private static final Pattern FULL_LOWERCASE_SHA = Pattern.compile("[0-9a-f]{40}");

    @Test
    void theBuiltJarNamesItsExactCheckoutHead() throws Exception {
        Path moduleJar = configuredModuleJar();
        String checkoutHead = independentlyResolvedHead();

        try (JarFile jar = new JarFile(moduleJar.toFile())) {
            Manifest manifest = jar.getManifest();
            assertNotNull(manifest,
                "the real LatteX jar has no manifest, so it cannot carry source lineage");

            String stampedRevision = manifest.getMainAttributes().getValue(REVISION_ATTRIBUTE);
            assertNotNull(stampedRevision,
                "the real LatteX jar manifest omits " + REVISION_ATTRIBUTE);
            assertTrue(FULL_LOWERCASE_SHA.matcher(stampedRevision).matches(),
                REVISION_ATTRIBUTE + " must be one lowercase full 40-character Git SHA, got `"
                    + stampedRevision + "`");
            assertEquals(checkoutHead, stampedRevision,
                "the jar lineage stamp does not identify the checkout HEAD used to build it");
        }
    }

    private static Path configuredModuleJar() {
        String configured = System.getProperty("lattex.moduleJar");
        assertNotNull(configured,
            "system property lattex.moduleJar was not set by the Gradle test task");
        Path moduleJar = Path.of(configured);
        assertTrue(Files.isRegularFile(moduleJar),
            "configured LatteX module jar does not exist: " + moduleJar);
        return moduleJar;
    }

    private static String independentlyResolvedHead() throws IOException, InterruptedException {
        Process git = new ProcessBuilder("git", "rev-parse", "--verify", "HEAD")
            .directory(Path.of("").toAbsolutePath().toFile())
            .redirectErrorStream(true)
            .start();
        if (!git.waitFor(30, TimeUnit.SECONDS)) {
            git.destroyForcibly();
            throw new AssertionError("git rev-parse --verify HEAD did not finish within 30 seconds");
        }
        String output = new String(git.getInputStream().readAllBytes(), StandardCharsets.UTF_8)
            .trim();
        assertEquals(0, git.exitValue(),
            "git rev-parse --verify HEAD failed while independently checking the jar: " + output);
        assertTrue(FULL_LOWERCASE_SHA.matcher(output).matches(),
            "git rev-parse --verify HEAD did not return a lowercase full 40-character SHA: `"
                + output + "`");
        return output;
    }
}
