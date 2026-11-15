plugins {
    id("dhe.embedded-spring-app")
}

description = "Host: Spring Boot running role B."

springBoot {
    mainClass = "io.github.cmh.dhe.apps.dhcb.spring.DhcBSpringApp"
}

dependencies {
    implementation(project(":apps:dhc-b-module"))
    implementation(project(":framework:embed-spring"))
    implementation(libs.spring.boot.starter.web)
}
