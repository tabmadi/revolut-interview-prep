import net.ltgt.gradle.errorprone.errorprone
import net.ltgt.gradle.nullaway.nullaway

plugins {
    application
    jacoco
    alias(libs.plugins.spotless)
    alias(libs.plugins.errorprone)
    alias(libs.plugins.nullaway)
    alias(libs.plugins.shadow)
}

group = "io.github.tabmadi"

version = "0.0.0"

// Live-coding ergonomics: the full static-analysis gate is opt-in.
//
//   ./gradlew test            -> fast, warnings stay warnings
//   ./gradlew check -Pstrict  -> -Werror + NullAway as errors + coverage floor
//
// Nothing is more expensive in a timed interview than a build that refuses to
// compile because a parameter is missing an annotation. Strictness is for the
// commit, not for the keystroke.
val strict = providers.gradleProperty("strict").isPresent

repositories { mavenCentral() }

java {
    toolchain {
        languageVersion =
            JavaLanguageVersion.of(
                libs.versions.java
                    .get()
                    .toInt(),
            )
    }
}

application { mainClass = "io.github.tabmadi.app.Main" }

dependencies {
    implementation(libs.typesafe.config)
    implementation(libs.slf4j.api)
    compileOnly(libs.jspecify)
    runtimeOnly(libs.logback.classic)

    errorprone(libs.errorprone.core)
    errorprone(libs.nullaway)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj.core)
    testImplementation(libs.awaitility)
    testCompileOnly(libs.jspecify)
    testRuntimeOnly(libs.junit.platform.launcher)
}

nullaway { annotatedPackages.add("io.github.tabmadi") }

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.compilerArgs.add("-Xlint:all")
    if (strict) options.compilerArgs.add("-Werror")
    options.errorprone {
        disableWarningsInGeneratedCode = true
        if (strict) nullaway { error() } else nullaway { warn() }
    }
}

spotless {
    java {
        target("src/**/*.java")
        palantirJavaFormat(
            libs.versions.palantir.java.format
                .get(),
        )
        removeUnusedImports()
        formatAnnotations()
        trimTrailingWhitespace()
        endWithNewline()
    }
    kotlinGradle { ktlint() }
    format("misc") {
        target("*.md", "*.yml", ".gitignore", ".env.example")
        trimTrailingWhitespace()
        endWithNewline()
    }
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "skipped", "failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
    // Concurrency tests are slow and flaky when starved; give them their own threads
    // rather than sharing one JVM lane with everything else.
    maxParallelForks = (Runtime.getRuntime().availableProcessors() / 2).coerceAtLeast(1)
    // A hung test is a failed test. Without this a deadlock bug stalls the build
    // instead of reporting itself.
    systemProperty("junit.jupiter.execution.timeout.testable.method.default", "30s")
    if (strict) finalizedBy(tasks.jacocoTestReport)
}

// The entry point is thin glue and is covered end-to-end, not by unit tests.
val coveredClasses =
    files(
        sourceSets.main.get().output.classesDirs.map {
            fileTree(it) { exclude("io/github/tabmadi/app/Main.class") }
        },
    )

tasks.jacocoTestReport {
    dependsOn(tasks.test)
    classDirectories.setFrom(coveredClasses)
    reports {
        xml.required = true
        html.required = true
    }
}

tasks.jacocoTestCoverageVerification {
    dependsOn(tasks.jacocoTestReport)
    classDirectories.setFrom(coveredClasses)
    violationRules {
        rule {
            limit {
                counter = "LINE"
                minimum = "0.80".toBigDecimal()
            }
        }
    }
}

if (strict) tasks.check { dependsOn(tasks.jacocoTestCoverageVerification) }

tasks.shadowJar {
    archiveFileName = "app.jar"
    destinationDirectory = layout.projectDirectory.dir("bin")
    mergeServiceFiles()
}

tasks.clean { delete(layout.projectDirectory.dir("bin")) }
