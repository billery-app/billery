package de.billery;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/**
 * The boundary between the core and the SaaS code (docs/architecture/multi-tenancy.md): the SaaS
 * package may use the core, the core never uses the SaaS package or the security libraries that
 * only the SaaS build brings. In the default build there is no SaaS code and these rules hold
 * trivially; the CI job that builds with {@code -Psaas} checks them for real.
 */
@AnalyzeClasses(packages = "de.billery", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

  @ArchTest
  static final ArchRule coreDoesNotDependOnTheSaasPackage =
      noClasses()
          .that()
          .resideOutsideOfPackage("de.billery.saas..")
          .should()
          .dependOnClassesThat()
          .resideInAPackage("de.billery.saas..")
          .allowEmptyShould(true);

  @ArchTest
  static final ArchRule coreDoesNotUseSpringSecurity =
      noClasses()
          .that()
          .resideOutsideOfPackage("de.billery.saas..")
          .should()
          .dependOnClassesThat()
          .resideInAnyPackage("org.springframework.security..")
          .allowEmptyShould(true);
}
