// framework/* and the role modules: a Java library that compiles against Deephaven.

import io.github.cmh.dhe.buildlogic.DeephavenRules
import io.github.cmh.dhe.buildlogic.lib
import io.github.cmh.dhe.buildlogic.libs

plugins {
    id("dhe.java-conventions")
    `java-library`
}

// Deephaven BOM enforced (F9), Kafka extension excluded (F2), JVM flags on tests (F3).
DeephavenRules.applyTo(project, bomConfiguration = "api")

dependencies {
    // F1: embedding means declaring our own Dagger component, so Dagger and its processor are
    // available to every Deephaven library. The version is the one Deephaven itself resolves.
    implementation(libs.lib("dagger"))
    annotationProcessor(libs.lib("dagger-compiler"))
}
