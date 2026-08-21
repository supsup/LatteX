plugins {
    `java-library`
    application
    `maven-publish`
}

group = "com.lattex"
// WHAT A CONSUMER PINS MUST BE IMMUTABLE. WHAT MAIN DECLARES MUST NOT PRETEND TO BE
// (plan ab8b2928 — these are two different claims and conflating them caused the defect
// below).
//
// The original rule here said "a real, immutable release version — NOT a rolling
// SNAPSHOT", meaning that a CONSUMER (Stafficy /docs) must pin an exact version that can
// never silently change under it. That part stands and is the whole point.
//
// It was read as forbidding main from ever saying SNAPSHOT, which is a different claim,
// and the result was strictly worse than the thing it was avoiding: on 2026-08-06 main
// declared "0.11.0" while src/main had drifted 38 files / +4887/-671 from the 0.11.0 cut
// (963121f), and the repo carried NO release tags at all — only retired/* branch markers.
// So "0.11.0" named no fixed tree. Two consumers pinning that string days apart got
// different bytes, and nothing anywhere went red. A bare release number that is not one
// LOOKS immutable and fails silently; a SNAPSHOT announces itself.
//
// THE RULE NOW:
//   - Between releases, main declares <next>-SNAPSHOT. It is honest about being a moving
//     target, and no consumer may pin it.
//   - A RELEASE is a commit that declares the bare version AND carries the matching
//     annotated tag. The tag is what makes the version immutable — a version string with
//     no tag behind it is a promise with no mechanism.
//   - Consumers pin ONLY a tagged release.
// VersionIdentityGuardTest enforces the second bullet, because the previous version of
// this rule was enforced by nothing but memory and lost 38 files to it.
//
// 0.12.0 (2026-08-06) was the FIRST tagged release. Release history lives in
// RELEASE_NOTES.md rather than accumulating here.
version = "0.13.0-SNAPSHOT"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
    withSourcesJar()
    withJavadocJar()
}

repositories {
    mavenCentral()
}

// LatteX ships ZERO runtime dependencies — the whole point. Test-scope only.
dependencies {
    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    // BrewShot: the real-browser harness (extracted FROM this repo's fx tests,
    // now vendored back as its own jar - github.com/supsup/BrewShot). Test
    // scope only; the zero-runtime-dependencies promise is untouched.
    testImplementation(files("libs/brewshot-0.9.0.jar"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    // GraalJS polyglot: executes lattex-fx.js INSIDE the JVM test task so runtime
    // behavior is pinned hermetically (no Node/jsdom toolchain; plan e09b28be).
    // Test scope only; the zero-runtime-dependencies promise is untouched.
    testImplementation("org.graalvm.polyglot:polyglot:24.2.2")
    testImplementation("org.graalvm.polyglot:js-community:24.2.2")
}

// Hermetic `test` (plan 32148cc8 S2, reviewer F1): every test — including the
// examples/-page GENERATORS (tagged "examples") and the BrewShot capture tests
// (tagged "capture") — runs in the normal suite, so their security / runtime /
// alphabet / safe-evaluator / grammar-pin assertions ALWAYS execute in CI.
// Hermeticity comes from WHERE they write, not from excluding assertions: under
// `test` the generators write into build/examples and the captures into build/,
// so `./gradlew test` never touches the working tree. Only `generateExamples`
// below (which sets -Dlattex.examples.write=true) writes the tracked examples/
// dir. Verify: run `test`, then `git status --porcelain` must be empty.
tasks.test {
    // THE CORE SUITE NEVER LAUNCHES A BROWSER (plan 8b7596e0 revived). Six real-browser BrewShot
    // pins carry @Tag("capture"); they run in `browserTest` below, which `check` still depends on
    // — so the assertions are never optional in CI, only separated from the fast core run.
    //
    // Excluded rather than deleted, and `check` still requires them, because a browser pin that
    // becomes opt-in is a browser pin nobody runs. The split is about WHERE they run, not whether.
    useJUnitPlatform {
        excludeTags("capture")
    }
    // Input for ReadmeCorpusFigureTest: the README itself. It is not a source file, so without
    // this Gradle holds :test UP-TO-DATE after a README-only edit and the guard never runs —
    // inert precisely when the prose it guards is being changed. (Observed on the sibling
    // Sirentide guard: BUILD SUCCESSFUL in 252ms because the task did not execute.)
    inputs.file(layout.projectDirectory.file("README.md"))
        .withPropertyName("readmeCorpusFigure")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    // Input for DeprecationSunsetTest: LatteX.java's SOURCE TEXT. This one is subtler than the
    // README case and I hit it by measurement, not foresight. LatteX.java IS a compiled source
    // file, so the obvious assumption is that editing it invalidates :test — but that test reads
    // the file's JAVADOC, and a comment-only edit compiles to BYTE-IDENTICAL bytecode. :compileJava
    // therefore produces unchanged output and :test stays UP-TO-DATE, so a mutation of the very
    // prose the guard exists to police silently never runs it. Observed: three attacks on the
    // deprecation text all "passed" while `:test UP-TO-DATE` scrolled past.
    inputs.file(layout.projectDirectory.file("src/main/java/com/lattex/api/LatteX.java"))
        .withPropertyName("deprecationSunsetSource")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    // Input for VersionIdentityGuardTest: build.gradle.kts itself, which that test READS at
    // runtime.
    //
    // IT IS NOT NEEDED FOR A VERSION CHANGE, and the obvious rationale for it is WRONG — I
    // measured before writing this comment rather than after. Changing `version` already
    // invalidates :test by a longer route: processResources declares
    // `inputs.property("lattexVersion", …)`, so a bump re-runs it, its output sits on the test
    // runtime classpath, and :test re-runs. Delete this declaration, flip the version, and the
    // guard still goes RED.
    //
    // WHAT IT ACTUALLY CATCHES is the edit that leaves `project.version` IDENTICAL while
    // breaking the guard's anchor — reformatting the declaration itself. Measured both ways:
    //     WITHOUT this input:  indent the version line (same value) -> :test UP-TO-DATE, GREEN
    //                          (the non-vacuity assertion never runs; the guard is silently dead)
    //     WITH this input:     the same edit -> :test RUNS, both tests RED
    // That is the silent-clean case — a guard that stops being able to SEE its target while
    // nothing reports a problem. Keep it, for that reason and not the plausible one.
    inputs.file(layout.projectDirectory.file("build.gradle.kts"))
        .withPropertyName("versionDeclaration")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    // Input for ModularBoundaryTest: the built MODULAR jar. That test compiles a genuine
    // separate named module against it with the real javac (a same-module unit test cannot
    // prove a JPMS boundary — the module system does not enforce a module against itself),
    // then loads that module in its own ModuleLayer and runs it. It must be the jar and not
    // the exploded class dir: the consumer actually renders, which needs the bundled font
    // resource, and the jar is also what a real downstream consumer puts on its module path.
    // Declared as a task input so the check re-runs whenever the module changes.
    val moduleJar = tasks.jar.flatMap { it.archiveFile }
    inputs.file(moduleJar).withPropertyName("lattexModuleJar")
    doFirst {
        systemProperty("lattex.moduleJar", moduleJar.get().asFile.absolutePath)
    }
}

// Regenerates the tracked examples/ artifacts on demand: the HTML pages ("examples"
// generators, byte-identical for an unchanged emitter) plus the BrewShot visual
// references ("capture" tests re-run with -Dlattex.examples.write=true so their
// PNG/GIF output lands beside the pages; references, not byte-goldens — animation
// frames differ run to run). NOT wired into `check`/`build`; run explicitly, review
// the diff, commit.
// The real-browser BrewShot pins (effects page, fx lifecycle, GIF liveness, fx gallery,
// rendered-error blob, interactive-math runtime) — everything tagged "capture". Split out of the
// core suite so `./gradlew test` never launches host Chrome, which made the fast path slow and
// made a Chrome-less machine look like a failing one. Honors the existing LATTEX_REQUIRE_BROWSER
// convention: without a browser these skip, with LATTEX_REQUIRE_BROWSER=1 they fail loud.
val browserTest by tasks.registering(Test::class) {
    group = "verification"
    description = "Real-browser BrewShot pins (tag \"capture\"); launches host Chrome."
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    shouldRunAfter(tasks.test)
    useJUnitPlatform {
        includeTags("capture")
    }
}

tasks.check {
    dependsOn(browserTest)
}

val generateExamples by tasks.registering(Test::class) {
    group = "documentation"
    description = "Regenerates the tracked examples/ pages + BrewShot visual references."
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform {
        includeTags("examples", "capture")
    }
    systemProperty("lattex.examples.write", "true")
    outputs.upToDateWhen { false } // regeneration is the point — never skip as up-to-date
}

// Single-source the version: stamp `project.version` into lattex-version.properties
// at build time, so the CLI (Main) and its test read the build version from one
// place — no hand-synced constant that drifts from build.gradle (it did: the CLI
// hardcode read 0.2.1 against a 0.5.0 artifact). Present in both the jar and the
// test classpath (unlike the jar manifest, which is null under `gradle test`).
tasks.processResources {
    // The version must be a declared task input: without it an incremental build
    // after a version bump keeps the previously-expanded properties file, and the
    // jar reports the OLD version (observed on the 0.8.0 → 0.9.0 bump).
    inputs.property("lattexVersion", project.version.toString())
    filesMatching("lattex-version.properties") {
        expand(mapOf("version" to project.version.toString()))
    }
}

// ---- CLI (S7): JVM-mode entry point + native-image binary --------------------

application {
    // The module system knows the main class; a modular `run` needs both.
    mainModule = "com.lattex"
    mainClass = "com.lattex.cli.Main"
}

// Make the plain library jar directly launchable: `java -jar build/libs/lattex-<ver>.jar`.
// (module-info is present, so -jar launches via this Main-Class on the classpath.)
val gitHead = providers.exec {
    commandLine("git", "rev-parse", "--verify", "HEAD")
    workingDir(rootDir)
    isIgnoreExitValue = true
}
val implementationScmRevision = providers.provider {
    val result = gitHead.result.get()
    val revision = gitHead.standardOutput.asText.get().trim()
    if (result.exitValue != 0) {
        throw GradleException(
            "Cannot stamp Implementation-SCM-Revision: " +
                "`git rev-parse --verify HEAD` exited ${result.exitValue}."
        )
    }
    if (!revision.matches(Regex("[0-9a-f]{40}"))) {
        throw GradleException(
            "Cannot stamp Implementation-SCM-Revision: expected one lowercase full " +
                "40-character Git SHA, got `${revision.ifEmpty { "<empty>" }}`."
        )
    }
    revision
}

tasks.jar {
    // HEAD is source identity even when source bytes are unchanged, so it must be a task input.
    // The lazy provider keeps unrelated Gradle tasks usable when Git is unavailable; `jar`
    // itself fails closed instead of stamping an unknown or silently omitting provenance.
    inputs.property("implementationScmRevision", implementationScmRevision)
    doFirst {
        manifest.attributes["Implementation-SCM-Revision"] = implementationScmRevision.get()
    }
    manifest {
        attributes(
            "Main-Class" to "com.lattex.cli.Main",
            "Implementation-Title" to "LatteX",
            "Implementation-Version" to project.version.toString(),
        )
    }
}

// ---- Publishing (plan 38cf48e4) -------------------------------------------------
//
// A versioned, immutable Maven artifact so downstream (Stafficy /docs) resolves
// `com.lattex:lattex:<version>` by coordinate instead of a hand-vendored SNAPSHOT
// jar that goes stale in hours. `./gradlew publishToMavenLocal` installs it into
// ~/.m2 (resolvable on-host); a real remote (GitHub Packages / internal) can be
// added later as a repositories{} entry without touching the publication.
publishing {
    publications {
        create<MavenPublication>("maven") {
            // The `java` component carries the main jar plus the sources & javadoc
            // jars (withSourcesJar()/withJavadocJar() above).
            from(components["java"])
            pom {
                name = "LatteX"
                description = "Clean-room, pure-Java LaTeX-math to SVG renderer; zero runtime dependencies."
                licenses {
                    license {
                        name = "Apache-2.0"
                        url = "https://www.apache.org/licenses/LICENSE-2.0"
                    }
                }
            }
        }
    }
}

// Build a standalone `lattex` native binary with GraalVM native-image.
//
// This does NOT use the Gradle toolchain (which may be a stock JDK); it shells
// out to `native-image`, resolved from (in order): the GRAALVM_HOME env var, the
// org.graalvm.home Gradle property, or `native-image` on PATH. Run with a GraalVM
// for JDK 25 selected, e.g. `sdk use java 25-graalce` then `./gradlew nativeImage`.
//
// Reachability metadata (the bundled font resource) already ships under
// src/main/resources/META-INF/native-image/, so native-image finds it on the jar.
val nativeImageOutputDir = layout.buildDirectory.dir("native")

val nativeImage by tasks.registering(Exec::class) {
    group = "build"
    description = "Builds the standalone `lattex` native binary via GraalVM native-image."
    dependsOn(tasks.jar)

    val jarFile = tasks.jar.flatMap { it.archiveFile }
    inputs.file(jarFile)
    outputs.dir(nativeImageOutputDir)

    doFirst {
        val outDir = nativeImageOutputDir.get().asFile
        outDir.mkdirs()

        val graalHome = System.getenv("GRAALVM_HOME")
            ?: (project.findProperty("org.graalvm.home") as String?)
        val nativeImageBin = if (graalHome != null) {
            val exe = File(graalHome, "bin/native-image")
            if (exe.exists()) exe.absolutePath else "native-image"
        } else {
            "native-image"
        }

        commandLine(
            nativeImageBin,
            "--no-fallback",
            "-o", File(outDir, "lattex").absolutePath,
            "-jar", jarFile.get().asFile.absolutePath,
        )
        logger.lifecycle("native-image: {} (jar: {})", nativeImageBin, jarFile.get().asFile)
    }
}
