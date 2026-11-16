import java.util.jar.JarFile

plugins {
    id("dhc.embedded-spring-app")
    id("io.spring.dependency-management") version "1.1.7" apply false
}

// Not allowed (F9). dhc.embedded-spring-app has to refuse it.
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

// What extractBootJar leaves in build/extracted (F7).
tasks.register("printExtracted") {
    val extracted = layout.buildDirectory.dir("extracted")
    dependsOn("extractBootJar")
    doLast {
        val directory = extracted.get().asFile
        println("extracted: " + directory.list()!!.sorted())
        val (classPath, mainClass, nested) = JarFile(directory.resolve("app.jar")).use { jar ->
            Triple(
                jar.manifest.mainAttributes.getValue("Class-Path").split(" "),
                jar.manifest.mainAttributes.getValue("Main-Class"),
                jar.entries().asSequence().count { it.name.endsWith(".jar") },
            )
        }
        val libraries = directory.resolve("lib").list()!!.toSet()
        println("main class: $mainClass")
        println("jars inside the thin jar: $nested")
        println("manifest entries: " + classPath.size)
        println("manifest entries missing from lib: " + classPath.count { it.removePrefix("lib/") !in libraries })
        println("lib has the server: " + libraries.any { it.startsWith("deephaven-server-jetty-") })
        println("jvm.args: " + directory.resolve("jvm.args").readLines())
    }
}
