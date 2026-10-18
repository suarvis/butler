plugins {
    id("butler.spring-library")
}

dependencies {
    implementation(project(":core"))
    implementation(platform(libs.spring.boot.bom))

    // Plain libraries rather than the Spring Boot JDBC / Flyway starters: this module decides
    // when a DataSource exists (only when butler.persistence.store=postgresql), so the
    // file-backed CLI never needs a database.
    implementation(libs.spring.boot.starter.base)
    implementation(libs.spring.jdbc)
    implementation(libs.hikaricp)
    implementation(libs.flyway.core)
    implementation(libs.flyway.postgresql)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlin.reflect)
    runtimeOnly(libs.postgresql)

    testImplementation(testFixtures(project(":core")))
    testImplementation(platform(libs.spring.boot.bom))
    testImplementation(libs.testcontainers)
    testRuntimeOnly(libs.postgresql)
}
