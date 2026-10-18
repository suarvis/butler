import org.gradle.api.artifacts.result.ResolvedComponentResult
import org.gradle.api.artifacts.result.ResolvedDependencyResult

plugins {
    id("butler.kotlin-library")
    `java-test-fixtures`
}

// core declares no dependencies on purpose: business logic depends on nothing but the
// Kotlin standard library (added by the Kotlin plugin) and the JDK.

val coreRuntimeRoot = configurations.runtimeClasspath.flatMap { it.incoming.resolutionResult.rootComponent }

val verifyCoreIsFrameworkFree by tasks.registering {
    group = "verification"
    description = "Fails if core's runtime classpath contains anything other than the Kotlin standard library."
    val root = coreRuntimeRoot
    doLast {
        val allowedGroups = setOf("org.jetbrains.kotlin", "org.jetbrains")
        val seen = mutableSetOf<String>()
        val offenders = mutableListOf<String>()
        val pending = ArrayDeque<ResolvedComponentResult>().apply { add(root.get()) }
        while (pending.isNotEmpty()) {
            pending.removeFirst().dependencies
                .filterIsInstance<ResolvedDependencyResult>()
                .forEach { dependency ->
                    val selected = dependency.selected
                    val module = selected.moduleVersion ?: return@forEach
                    val key = "${module.group}:${module.name}"
                    if (seen.add(key)) {
                        if (module.group !in allowedGroups) offenders += "$key:${module.version}"
                        pending.add(selected)
                    }
                }
        }
        if (offenders.isNotEmpty()) {
            throw GradleException(
                "core must not depend on frameworks or libraries, but its runtime classpath contains: " +
                    offenders.joinToString(),
            )
        }
    }
}

tasks.named("check") {
    dependsOn(verifyCoreIsFrameworkFree)
}
