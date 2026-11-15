plugins {
    id("dhe.embedded-java-app")
}

description = "Host: plain Java main running role B."

application {
    mainClass = "io.github.cmh.dhe.apps.dhcb.java.DhcBJavaApp"
}

dependencies {
    implementation(project(":apps:dhc-b-module"))
}
