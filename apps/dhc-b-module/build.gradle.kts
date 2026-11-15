plugins {
    id("dhe.groovy-app-module")
}

description = "Role B: subscribes to instance A and derives its own tables. A Groovy application-mode module plus the Java it calls."

dependencies {
    api(project(":framework:embed-core"))
    api(project(":framework:orders-model"))
}
