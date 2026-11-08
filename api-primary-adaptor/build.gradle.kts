import org.springframework.boot.gradle.tasks.bundling.BootJar

plugins {
    id("butler.spring-boot-application")
}

dependencies {
    implementation(project(":core"))
    // Runtime only: the primary adaptor can never reference secondary-adaptor classes at compile
    // time, but the running application needs its gateway implementations. ArchUnit double-checks.
    runtimeOnly(project(":secondary-adaptor"))

    implementation(platform(libs.spring.boot.bom))
    implementation(libs.spring.boot.starter.base)
    implementation(libs.kotlin.reflect)
    implementation(libs.picocli)
    implementation(libs.kotlinx.serialization.json)
    // The line editor for interactive use. The jdk11 variant leaves out the FFM terminal (Java 22
    // class files) and reaches the terminal through JNI or exec instead, so it runs on JDK 21.
    implementation(variantOf(libs.jline) { classifier("jdk11") })

    testImplementation(testFixtures(project(":core")))
    testImplementation(platform(libs.spring.boot.bom))
    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.archunit)
}

springBoot {
    mainClass.set("com.suarvis.butler.api.ButlerApplicationKt")
}

tasks.named<BootJar>("bootJar") {
    archiveFileName.set("butler.jar")
    manifest {
        attributes("Implementation-Title" to "butler", "Implementation-Version" to project.version)
    }
}

tasks.named<Jar>("jar") {
    enabled = false
}
