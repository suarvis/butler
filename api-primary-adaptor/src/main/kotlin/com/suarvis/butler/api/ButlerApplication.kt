package com.suarvis.butler.api

import org.springframework.boot.Banner
import org.springframework.boot.SpringApplication
import org.springframework.boot.WebApplicationType
import org.springframework.boot.autoconfigure.SpringBootApplication
import kotlin.system.exitProcess

/** Composition root. Scans every Butler package so secondary-adaptor beans join at runtime. */
@SpringBootApplication(scanBasePackages = ["com.suarvis.butler"])
class ButlerApplication {
    companion object {
        /** Runs one CLI invocation and returns its exit code; used by `main` and end-to-end tests. */
        fun run(vararg args: String): Int {
            val application =
                SpringApplication(ButlerApplication::class.java).apply {
                    setBannerMode(Banner.Mode.OFF)
                    setLogStartupInfo(false)
                    // CLI flags such as --model belong to picocli, not to Spring's property sources.
                    setAddCommandLineProperties(false)
                    setWebApplicationType(WebApplicationType.NONE)
                }
            val context = application.run(*args)
            return SpringApplication.exit(context)
        }
    }
}

fun main(args: Array<String>) {
    exitProcess(ButlerApplication.run(*args))
}
