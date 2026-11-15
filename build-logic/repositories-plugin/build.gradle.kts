plugins {
    `java-gradle-plugin`
}

group = "io.github.cmh.dhe.buildlogic"

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release = 17 // the oldest JVM Gradle 9 itself runs on
    options.compilerArgs.addAll(listOf("-Xlint:all", "-Werror"))
}

gradlePlugin {
    plugins {
        create("repositories") {
            id = "dhe.repositories"
            implementationClass = "io.github.cmh.dhe.buildlogic.repositories.DheRepositoriesPlugin"
        }
    }
}
