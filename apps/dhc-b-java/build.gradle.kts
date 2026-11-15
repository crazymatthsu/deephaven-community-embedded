plugins {
    id("dhc.embedded-java-app")
}

description = "Host: plain Java main running role B."

application {
    mainClass = "io.github.cmh.dhc.apps.dhcb.java.DhcBJavaApp"
}

dependencies {
    implementation(project(":apps:dhc-b-module"))
}
