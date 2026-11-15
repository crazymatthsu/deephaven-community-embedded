plugins {
    id("dhe.embedded-spring-app")
    id("io.spring.dependency-management") version "1.1.7" apply false
}

// Not allowed (F9). dhe.embedded-spring-app has to refuse it.
if (providers.gradleProperty("applyDependencyManagement").isPresent) {
    apply(plugin = "io.spring.dependency-management")
}

dependencies {
    implementation(libs.deephaven.server.jetty)
    implementation(libs.spring.boot.starter.web)
}

// What F9 looks like when it comes back: something forces the Jetty that Spring Boot 3.5 manages.
if (providers.gradleProperty("forceOlderJetty").isPresent) {
    configurations.configureEach {
        resolutionStrategy.force("org.eclipse.jetty:jetty-server:12.0.36")
    }
}

tasks.register("printJvmFlags") {
    val bootRun = tasks.named<JavaExec>("bootRun").map { it.allJvmArgs }
    val test = tasks.named<Test>("test").map { it.allJvmArgs }
    doLast {
        println("flags of bootRun: ${bootRun.get()}")
        println("flags of test: ${test.get()}")
    }
}
