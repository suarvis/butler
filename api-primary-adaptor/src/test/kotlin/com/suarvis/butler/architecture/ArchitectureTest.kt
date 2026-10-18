package com.suarvis.butler.architecture

import com.tngtech.archunit.core.domain.JavaClasses
import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.core.importer.ImportOption
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses
import org.junit.jupiter.api.Test

/**
 * Enforces the clean-architecture dependency rule on the compiled classes of all three modules.
 * The api-primary-adaptor test classpath contains core and (at runtime) secondary-adaptor.
 */
class ArchitectureTest {
    private val classes: JavaClasses =
        ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages(BUTLER)

    @Test
    fun `core depends only on the JDK, the Kotlin standard library and itself`() {
        classes()
            .that().resideInAPackage(CORE)
            .should().onlyDependOnClassesThat().resideInAnyPackage(CORE, "java..", "kotlin..", "org.jetbrains.annotations..")
            .check(classes)
    }

    @Test
    fun `the primary adaptor never references the secondary adaptor`() {
        noClasses().that().resideInAPackage(API)
            .should().dependOnClassesThat().resideInAPackage(SECONDARY)
            .check(classes)
    }

    @Test
    fun `the secondary adaptor never references the primary adaptor`() {
        noClasses().that().resideInAPackage(SECONDARY)
            .should().dependOnClassesThat().resideInAPackage(API)
            .check(classes)
    }

    @Test
    fun `only the secondary adaptor touches databases`() {
        noClasses().that().resideOutsideOfPackage(SECONDARY)
            .should().dependOnClassesThat().resideInAnyPackage("java.sql..", "javax.sql..", "org.springframework.jdbc..", "org.flywaydb..", "com.zaxxer..")
            .check(classes)
    }

    @Test
    fun `gateways are interfaces`() {
        classes().that().resideInAPackage("$BUTLER.core.gateway..").and().areTopLevelClasses()
            .should().beInterfaces()
            .check(classes)
    }

    @Test
    fun `gateway implementations are named GatewayImpl`() {
        classes().that().resideInAPackage("$BUTLER.secondary.gateway.impl..").and().areTopLevelClasses()
            .should().haveSimpleNameEndingWith("GatewayImpl")
            .check(classes)
    }

    @Test
    fun `use cases are named UseCase`() {
        classes().that().resideInAPackage("$BUTLER.core.usecase..").and().areTopLevelClasses()
            .should().haveSimpleNameEndingWith("UseCase")
            .check(classes)
    }

    @Test
    fun `persistence mappers are named PersistenceMapper`() {
        classes().that().resideInAPackage("$BUTLER.secondary.persistence..mapper..").and().areTopLevelClasses()
            .should().haveSimpleNameEndingWith("PersistenceMapper")
            .check(classes)
    }

    @Test
    fun `provider client mappers are named ClientMapper`() {
        classes().that().resideInAPackage("$BUTLER.secondary.client..mapper..").and().areTopLevelClasses()
            .should().haveSimpleNameEndingWith("ClientMapper")
            .check(classes)
    }

    @Test
    fun `only the secondary adaptor makes HTTP calls`() {
        noClasses().that().resideOutsideOfPackage(SECONDARY)
            .should().dependOnClassesThat().resideInAnyPackage("java.net.http..")
            .check(classes)
    }

    @Test
    fun `CLI mappers are named CliMapper`() {
        classes().that().resideInAPackage("$BUTLER.api.cli.mapper..").and().areTopLevelClasses()
            .should().haveSimpleNameEndingWith("CliMapper")
            .check(classes)
    }

    @Test
    fun `authorization rules are named Rule`() {
        classes().that().resideInAPackage("$BUTLER.api.security.authorization.rules..").and().areTopLevelClasses()
            .and().haveSimpleNameNotEndingWith("Request")
            .should().haveSimpleNameEndingWith("Rule")
            .check(classes)
    }

    @Test
    fun `CLI output DTOs never expose domain types`() {
        noClasses().that().resideInAPackage("$BUTLER.api.cli.dto..")
            .should().dependOnClassesThat().resideInAPackage("$BUTLER.core.domain..")
            .check(classes)
    }

    @Test
    fun `persistence models never expose domain types`() {
        noClasses().that().resideInAnyPackage("$BUTLER.secondary.persistence..entity..")
            .should().dependOnClassesThat().resideInAPackage("$BUTLER.core..")
            .check(classes)
    }

    private companion object {
        const val BUTLER = "com.suarvis.butler"
        const val CORE = "$BUTLER.core.."
        const val API = "$BUTLER.api.."
        const val SECONDARY = "$BUTLER.secondary.."
    }
}
