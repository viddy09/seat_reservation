package com.paytmmoney.seatreservation;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/** Layering rules from docs/DESIGN.md §10, enforced on every build. */
@AnalyzeClasses(packages = "com.paytmmoney.seatreservation", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

    private static final String BASE = "com.paytmmoney.seatreservation";

    @ArchTest
    static final ArchRule domainDependsOnNothing = noClasses()
            .that()
            .resideInAPackage(BASE + ".domain..")
            .should()
            .dependOnClassesThat()
            .resideOutsideOfPackages(BASE + ".domain..", "java..")
            .allowEmptyShould(true);

    @ArchTest
    static final ArchRule serviceDoesNotDependOnApi = noClasses()
            .that()
            .resideInAPackage(BASE + ".service..")
            .should()
            .dependOnClassesThat()
            .resideInAPackage(BASE + ".api..")
            .allowEmptyShould(true);

    @ArchTest
    static final ArchRule persistenceDependsOnlyOnDomain = noClasses()
            .that()
            .resideInAPackage(BASE + ".persistence..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage(BASE + ".api..", BASE + ".service..", BASE + ".security..", BASE + ".observability..")
            .allowEmptyShould(true);

    @ArchTest
    static final ArchRule apiDoesNotTouchPersistence = noClasses()
            .that()
            .resideInAPackage(BASE + ".api..")
            .should()
            .dependOnClassesThat()
            .resideInAPackage(BASE + ".persistence..")
            .allowEmptyShould(true);

    @ArchTest
    static final ArchRule onlyPersistenceRunsSql = noClasses()
            .that()
            .resideOutsideOfPackages(BASE + ".persistence..", BASE + ".config..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage("org.springframework.jdbc..", "javax.sql..", "java.sql..");
}
