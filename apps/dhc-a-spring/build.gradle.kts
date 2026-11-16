plugins {
    id("dhc.embedded-spring-app")
}

description = "Host: Spring Boot running role A."

springBoot {
    mainClass = "io.github.cmh.dhc.apps.dhca.spring.DhcASpringApp"
}

dependencies {
    implementation(project(":apps:dhc-a-module"))
    implementation(project(":framework:embed-spring"))
    implementation(libs.spring.boot.starter.web)
    implementation(libs.spring.boot.starter.actuator)

    testImplementation(project(":framework:embed-testkit"))
    testImplementation(libs.spring.boot.starter.test)
}
