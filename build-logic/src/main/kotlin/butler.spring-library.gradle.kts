// Conventions for the adaptor modules: Kotlin + Spring (open classes for Spring proxies) and
// kotlinx.serialization for their own JSON models. core never applies this plugin.

plugins {
    id("butler.kotlin-library")
    id("org.jetbrains.kotlin.plugin.spring")
    id("org.jetbrains.kotlin.plugin.serialization")
}
