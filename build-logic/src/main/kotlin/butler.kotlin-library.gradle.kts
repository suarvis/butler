import org.gradle.api.tasks.testing.logging.TestExceptionFormat

// Shared conventions for every Butler module: Kotlin on a JDK 21 toolchain (virtual threads) and
// JUnit Platform tests. Gradle itself may run on an older JDK; compilation and tests always use 21.

plugins {
    `java-library`
    id("org.jetbrains.kotlin.jvm")
}

val catalog = extensions.getByType<VersionCatalogsExtension>().named("libs")

kotlin {
    jvmToolchain(21)
    compilerOptions {
        freeCompilerArgs.add("-Xjsr305=strict")
    }
}

dependencies {
    "testImplementation"(platform(catalog.findLibrary("junit-bom").get()))
    "testImplementation"(catalog.findLibrary("junit-jupiter").get())
    "testRuntimeOnly"(catalog.findLibrary("junit-platform-launcher").get())
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    testLogging {
        events("failed", "skipped")
        exceptionFormat = TestExceptionFormat.FULL
    }
}
