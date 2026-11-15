// Every app: verifyDeephavenAlignment, the guard against F9 coming back when a host adds a
// dependency. It is part of `check`.

import io.github.cmh.dhc.buildlogic.DeephavenAlignmentExtension
import io.github.cmh.dhc.buildlogic.DeephavenRules
import io.github.cmh.dhc.buildlogic.VerifyDeephavenAlignment
import io.github.cmh.dhc.buildlogic.lib
import io.github.cmh.dhc.buildlogic.libs
import org.gradle.api.artifacts.component.ModuleComponentIdentifier

plugins {
    java
}

val runtimeClasspath = configurations.named("runtimeClasspath")

val alignment = extensions.create<DeephavenAlignmentExtension>("deephavenAlignment")

// The two helper configurations below are detached, that is not part of `configurations`, so a
// project-wide rule such as configurations.all { resolutionStrategy.force(...) } cannot reach
// them. If it could, the rule would change the reference together with the application and the
// comparison would pass. They resolve the same kind of thing as runtimeClasspath: runtime jars
// for this JVM.
fun detachedLikeRuntimeClasspath(): Configuration = configurations.detachedConfiguration().apply {
    attributes {
        attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage.JAVA_RUNTIME))
        attribute(Category.CATEGORY_ATTRIBUTE, objects.named(Category.LIBRARY))
        attribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE, objects.named(LibraryElements.JAR))
        attribute(Bundling.BUNDLING_ATTRIBUTE, objects.named(Bundling.EXTERNAL))
        attribute(TargetJvmEnvironment.TARGET_JVM_ENVIRONMENT_ATTRIBUTE, objects.named(TargetJvmEnvironment.STANDARD_JVM))
        attributeProvider(
            TargetJvmVersion.TARGET_JVM_VERSION_ATTRIBUTE,
            provider { runtimeClasspath.get().attributes.getAttribute(TargetJvmVersion.TARGET_JVM_VERSION_ATTRIBUTE) },
        )
    }
    exclude(group = DeephavenRules.DEEPHAVEN_GROUP, module = DeephavenRules.KAFKA_EXTENSION) // F2
}

// Step 1, which Deephaven modules does this application use? The application's dependencies are
// resolved once more, without its version constraints, only to read the io.deephaven module
// names. Reading them from runtimeClasspath itself would be a cycle for a Spring host, whose
// runtimeClasspath is pinned to the result of step 2.
val deephavenModules = detachedLikeRuntimeClasspath()
deephavenModules.dependencies.addAllLater(
    provider { runtimeClasspath.get().allDependencies.map { it.copy() } }
)

// Step 2, "Deephaven alone": those modules and nothing else, resolved under the Deephaven BOM.
// What they resolve to is what the application has to resolve to as well. Because the modules
// are read from the application, a Deephaven module a host adds later is compared without
// anyone having to list it here.
val deephavenAlone = detachedLikeRuntimeClasspath()
deephavenAlone.dependencies.add(dependencies.enforcedPlatform(libs.lib(DeephavenRules.DEEPHAVEN_BOM).get()))
deephavenAlone.dependencies.addAllLater(
    provider {
        deephavenModules.incoming.resolutionResult.allComponents
            .mapNotNull { it.id as? ModuleComponentIdentifier }
            .filter { it.group == DeephavenRules.DEEPHAVEN_GROUP && it.module != DeephavenRules.DEEPHAVEN_BOM }
            .map { project.dependencies.create("${it.group}:${it.module}") }
    }
)
alignment.reference = deephavenAlone.incoming.resolutionResult.rootComponent

// Step 3, compare.
val verifyDeephavenAlignment = tasks.register<VerifyDeephavenAlignment>("verifyDeephavenAlignment") {
    group = "verification"
    description = "Fails if a library resolves to a different version than Deephaven alone resolves it to."
    applicationPath = project.path
    applicationRoot = runtimeClasspath.flatMap { it.incoming.resolutionResult.rootComponent }
    referenceRoot = alignment.reference
    report = layout.buildDirectory.file("reports/deephaven-alignment/report.txt")
}

tasks.named("check") {
    dependsOn(verifyDeephavenAlignment)
}
