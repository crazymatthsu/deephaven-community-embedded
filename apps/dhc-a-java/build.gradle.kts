plugins {
    id("dhe.embedded-java-app")
}

description = "Host: plain Java main running role A."

application {
    mainClass = "io.github.cmh.dhe.apps.dhca.java.DhcAJavaApp"
}

dependencies {
    implementation(project(":apps:dhc-a-module"))
}
