plugins {
    id("dhc.embedded-java-app")
}

application {
    mainClass = "fixture.Main" // never run; the start script task needs a name
}

dependencies {
    if (!providers.gradleProperty("withoutDeephaven").isPresent) {
        implementation(libs.deephaven.server.jetty)
    }
}

// What F9 looks like when it comes back: something forces the Jetty that Spring Boot 3.5 manages.
if (providers.gradleProperty("forceOlderJetty").isPresent) {
    configurations.configureEach {
        resolutionStrategy.force("org.eclipse.jetty:jetty-server:12.0.36")
    }
}

tasks.register("printJvmFlags") {
    val run = tasks.named<JavaExec>("run").map { it.allJvmArgs }
    val test = tasks.named<Test>("test").map { it.allJvmArgs }
    val startScripts = tasks.named<CreateStartScripts>("startScripts").map { it.defaultJvmOpts?.toList().orEmpty() }
    doLast {
        println("flags of run: ${run.get()}")
        println("flags of test: ${test.get()}")
        println("flags of startScripts: ${startScripts.get()}")
    }
}
