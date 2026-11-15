plugins {
    id("dhc.java-conventions")
}

// Not allowed: repositories come only from dhc.repositories and config/repos/repos.env.
repositories {
    mavenCentral()
}
