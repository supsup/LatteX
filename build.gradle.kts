plugins {
    `java-library`
    application
}

group = "com.lattex"
version = "0.1.0-SNAPSHOT"

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
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}

// ---- CLI (S7): JVM-mode entry point + native-image binary --------------------

application {
    // The module system knows the main class; a modular `run` needs both.
    mainModule = "com.lattex"
    mainClass = "com.lattex.cli.Main"
}

// Make the plain library jar directly launchable: `java -jar build/libs/lattex-<ver>.jar`.
// (module-info is present, so -jar launches via this Main-Class on the classpath.)
tasks.jar {
    manifest {
        attributes(
            "Main-Class" to "com.lattex.cli.Main",
            "Implementation-Title" to "LatteX",
            "Implementation-Version" to project.version.toString(),
        )
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
