plugins {
    id("dhe.embedded-spring-app")
}

description = "Host: Spring Boot running role A."

springBoot {
    mainClass = "io.github.cmh.dhe.apps.dhca.spring.DhcASpringApp"
}

dependencies {
    implementation(project(":apps:dhc-a-module"))
    implementation(project(":framework:embed-spring"))
    implementation(libs.spring.boot.starter.web)
}
