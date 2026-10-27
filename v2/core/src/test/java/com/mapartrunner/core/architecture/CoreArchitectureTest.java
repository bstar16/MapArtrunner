package com.mapartrunner.core.architecture;

import com.mapartrunner.core.model.BuildPlan;
import com.mapartrunner.core.run.RunLifecycle;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.fields;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class CoreArchitectureTest {
    private static JavaClasses coreClasses;

    @BeforeAll
    static void importProductionCore() {
        coreClasses = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.mapartrunner.core");
        assertFalse(coreClasses.isEmpty(), "Architecture rules must scan production core classes");
        assertNotNull(coreClasses.get(BuildPlan.class));
        assertNotNull(coreClasses.get(RunLifecycle.class));
    }

    @Test
    void coreDoesNotDependOnPlatformOrAdapterClasses() {
        noClasses().should().dependOnClassesThat().resideInAnyPackage(
                        "net.minecraft..", "net.fabricmc..", "org.spongepowered.asm..",
                        "baritone..", "com.mapartrunner.fabric..")
                .allowEmptyShould(false)
                .check(coreClasses);
    }

    @Test
    void corePackagesHaveNoCycles() {
        // Imported classes are core-only; include its root and every nested package as separate slices.
        slices().matching("com.mapartrunner.(**)").should().beFreeOfCycles()
                .allowEmptyShould(false)
                .check(coreClasses);
    }

    @Test
    void modelFieldsAreFinal() {
        fields().that().areDeclaredInClassesThat().resideInAPackage("com.mapartrunner.core.model..")
                .should().beFinal()
                .allowEmptyShould(false)
                .check(coreClasses);
    }
}
