plugins {
    `kotlin-dsl`
}

dependencies {
    implementation(libs.plugin.kotlin.gradle)
    implementation(libs.plugin.kotlin.allopen)
    implementation(libs.plugin.kotlin.serialization)
    implementation(libs.plugin.spring.boot)
}
