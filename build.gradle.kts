// jcma build — M1 core engine + index.
//
// Locked M1 decisions encoded here:
//  • Java 25 (records, virtual threads, FFM, structured concurrency) — PRD §9.
//  • GraalVM native-image from task 1 onward: every task keeps `nativeCompile` green and runs a
//    native smoke of the `jcma` CLI. M0 Spike C proved the build *order* is load-bearing
//    (agent-trace → bundle metadata → native build); the reachability seed lives as a committed
//    resource under src/main/resources/META-INF/native-image so nativeCompile always sees it.
//  • Engine = JavaParser + JavaSymbolSolver (M0 GO verdict).

plugins {
    application
    id("org.graalvm.buildtools.native") version "1.1.1"
}

group = "jcma"
version = "0.1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    implementation("com.github.javaparser:javaparser-symbol-solver-core:3.28.2")

    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
        // GraalVM provides native-image; use it for compile+test+native so the toolchain is one
        // thing. (SDKMAN-installed 25.0.2-graalce is auto-detected by Gradle.)
        vendor = JvmVendorSpec.GRAAL_VM
    }
}

application {
    mainClass = "jcma.cli.Main"
}

tasks.test {
    useJUnitPlatform()
}

graalvmNative {
    // The native plugin does not inherit `java.toolchain`; without this it resolves native-image
    // from JAVA_HOME (Temurin here) and fails "JDK isn't a GraalVM distribution". Point its
    // launcher at the detected GraalVM (SDKMAN 25.0.2-graalce) explicitly. Don't remove.
    binaries.all {
        javaLauncher = javaToolchains.launcherFor {
            languageVersion = JavaLanguageVersion.of(25)
            vendor = JvmVendorSpec.GRAAL_VM
        }
    }
    binaries {
        named("main") {
            imageName = "jcma"
            mainClass = "jcma.cli.Main"
            // M0 Spike C findings, carried verbatim:
            //  --no-fallback                 : a real native image, never a JVM-fallback launcher.
            //  --enable-native-access        : FFM mmap store (§5.1) uses Arena/MemorySegment.
            //  +UnlockExperimentalVMOptions
            //  +SharedArenaSupport           : Arena.ofShared() across query threads (else
            //                                  UnsupportedFeatureError at runtime).
            buildArgs.add("--no-fallback")
            buildArgs.add("--enable-native-access=ALL-UNNAMED")
            buildArgs.add("-H:+UnlockExperimentalVMOptions")
            buildArgs.add("-H:+SharedArenaSupport")
        }
    }
    // `-Pagent` enables the native-image tracing agent on `test`/`run`; `metadataCopy` folds the
    // agent's output into src/main/resources/META-INF/native-image (the committed seed), encoding
    // the agent → bundle → nativeCompile order M0 proved load-bearing.
    agent {
        defaultMode = "standard"
        metadataCopy {
            inputTaskNames.add("test")
            outputDirectories.add("src/main/resources/META-INF/native-image")
            mergeWithExisting = true
        }
    }
}
