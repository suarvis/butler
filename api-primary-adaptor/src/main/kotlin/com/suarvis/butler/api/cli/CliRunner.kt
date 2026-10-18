package com.suarvis.butler.api.cli

import com.suarvis.butler.api.cli.command.ButlerCommand
import com.suarvis.butler.api.cli.exception.CliExceptionHandler
import org.springframework.beans.factory.config.AutowireCapableBeanFactory
import org.springframework.boot.CommandLineRunner
import org.springframework.boot.ExitCodeGenerator
import org.springframework.stereotype.Component
import picocli.CommandLine
import java.io.PrintWriter

/** Bridges Spring Boot and picocli: parses the arguments, runs one command, records the exit code. */
@Component
class CliRunner(
    private val beanFactory: AutowireCapableBeanFactory,
    private val exceptionHandler: CliExceptionHandler,
    private val console: CliConsole,
) : CommandLineRunner, ExitCodeGenerator {
    @Volatile
    private var exitCode = ExitCodes.OK

    override fun run(vararg args: String) {
        val arguments = args.toList()
        val commandLine =
            CommandLine(ButlerCommand::class.java, SpringCommandFactory(beanFactory))
                .setCaseInsensitiveEnumValuesAllowed(true)
                .setOut(PrintWriter(console.out, true))
                .setErr(PrintWriter(console.err, true))
                .setParameterExceptionHandler { error, _ -> exceptionHandler.handleParseError(error, arguments) }
                .setExecutionExceptionHandler { error, _, _ -> exceptionHandler.handleExecutionError(error, arguments) }
        // Root only: after `--resume latest /session delete x`, everything is a slash-command argument.
        commandLine.commandSpec.parser().stopAtPositional(true)
        exitCode = commandLine.execute(*args)
    }

    override fun getExitCode(): Int = exitCode
}

/** Lets picocli build Butler's command classes through Spring, so they get constructor injection. */
class SpringCommandFactory(private val beanFactory: AutowireCapableBeanFactory) : CommandLine.IFactory {
    private val fallback = CommandLine.defaultFactory()

    // picocli's generic signature allows nullable K; Spring's createBean requires a non-null type.
    @Suppress("UNCHECKED_CAST")
    override fun <K : Any?> create(cls: Class<K>): K =
        if (cls.name.startsWith(BUTLER_PACKAGE)) beanFactory.createBean(cls as Class<Any>) as K else fallback.create(cls)

    private companion object {
        const val BUTLER_PACKAGE = "com.suarvis.butler."
    }
}
