plugins {
    id("dhc.groovy-app-module")
}

description = "Role B: subscribes to instance A and derives its own tables. A Groovy application-mode module plus the Java it calls."

dependencies {
    api(project(":framework:embed-core"))
    api(project(":framework:orders-model"))
}

// Two-process tests (T3): instance A runs as a process of its own, from its installed
// distribution, and role B runs inside the test's JVM and subscribes to it.
val dhcAInstall = project(":apps:dhc-a-java").layout.buildDirectory.dir("install/dhc-a-java")

testing {
    suites {
        register<JvmTestSuite>("integrationTest") {
            useJUnitJupiter(libs.versions.junit.get())
            dependencies {
                implementation(project())
                implementation(project(":framework:embed-testkit"))
                runtimeOnly(libs.logback.classic)
            }
            targets.all {
                testTask.configure {
                    description = "Runs role B against instance A in a second process."
                    dependsOn(":apps:dhc-a-java:installDist")
                    systemProperty("dhc.a.install", dhcAInstall.get().asFile.absolutePath)
                    shouldRunAfter(tasks.named("test"))
                }
            }
        }
    }
}
