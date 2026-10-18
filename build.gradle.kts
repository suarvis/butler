// Module wiring lives in each module's build file; shared conventions live in build-logic.
// Dependency rule enforced here and by ArchUnit:
//   api-primary-adaptor -> core <- secondary-adaptor, core -> nothing.

tasks.register("architectureCheck") {
    group = "verification"
    description = "Runs the dependency-rule guards: core classpath check and ArchUnit rules."
    dependsOn(":core:verifyCoreIsFrameworkFree", ":api-primary-adaptor:test")
}
