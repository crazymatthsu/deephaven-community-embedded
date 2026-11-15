plugins {
    id("dhc.groovy-app-module")
}

description = "Role A: publishes mock order executions. A Groovy application-mode module plus the Java it calls."

dependencies {
    api(project(":framework:embed-core"))
    api(project(":framework:orders-model"))
}
