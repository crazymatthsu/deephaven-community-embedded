// Container images of the four hosts, and nothing else: no image is built by `check` or
// `assemble`, because building one needs a container runtime.
//
//   ./gradlew buildImages          all four: dhc/<app>:<version> and dhc/<app>:latest
//   ./gradlew :docker:buildImageDhcBSpring
//
// The runtime is docker if it is on the PATH, else podman; DHC_CONTAINER_RUNTIME says otherwise.
// The base image is <DHC_CONTAINER_REGISTRY>/<DHC_CONTAINER_BASE_IMAGE> from
// config/repos/repos.env, so a company registry is a change in that one file.

plugins {
    base
}

@Suppress("UNCHECKED_CAST")
val repos = gradle.extra["dhc.repos"] as Map<String, String> // config/repos/repos.env, set by dhc.repositories
val baseImage = repos.getValue("DHC_CONTAINER_REGISTRY").trimEnd('/') + "/" + repos.getValue("DHC_CONTAINER_BASE_IMAGE")

// Host, and which of the two Dockerfiles builds it.
val hosts = mapOf(
    "dhc-a-java" to "java",
    "dhc-b-java" to "java",
    "dhc-a-spring" to "spring",
    "dhc-b-spring" to "spring",
)

fun containerRuntime(): String {
    providers.environmentVariable("DHC_CONTAINER_RUNTIME").orNull?.let { return it }
    val path = providers.environmentVariable("PATH").orNull.orEmpty().split(File.pathSeparator)
    val suffixes = listOf("", ".exe")
    for (candidate in listOf("docker", "podman")) {
        if (path.any { directory -> suffixes.any { File(directory, candidate + it).canExecute() } }) {
            return candidate
        }
    }
    throw GradleException(
        "buildImages needs a container runtime: neither docker nor podman is on the PATH. " +
            "Install one, or name another with DHC_CONTAINER_RUNTIME."
    )
}

val imageTasks = hosts.map { (app, kind) ->
    val taskSuffix = app.split('-').joinToString("") { it.replaceFirstChar(Char::uppercase) }
    val context = layout.buildDirectory.dir("context/$app")
    val appProject = ":apps:$app"

    // Everything the image is made of, in one directory, so that the Dockerfile needs nothing
    // from outside its build context.
    val stage = tasks.register<Sync>("stageImage$taskSuffix") {
        description = "Collects what the image of $app is built from."
        into(context)
        if (kind == "java") {
            dependsOn("$appProject:installDist")
            from(rootProject.layout.projectDirectory.dir("apps/$app/build/install/$app")) { into("app") }
        } else {
            dependsOn("$appProject:extractBootJar")
            from(rootProject.layout.projectDirectory.dir("apps/$app/build/extracted")) { into("app") }
        }
        // The settings as checked: defaults.env, apps/ and env/. Not repos/, which is about building.
        dependsOn(":config:validateConfig")
        from(rootProject.layout.projectDirectory.dir("config")) {
            include("defaults.env", "apps/*.env", "env/*.env")
            into("config")
        }
        from(rootProject.layout.projectDirectory.file("scripts/lib.sh"))
        from(layout.projectDirectory) { include("entrypoint.sh", "healthcheck.sh") }
        from(layout.projectDirectory.file("Dockerfile.$kind-app")) { rename { "Dockerfile" } }
        // A Windows checkout may have given the scripts CRLF line ends; bash in the image wants LF.
        filesMatching(listOf("*.sh", "config/**/*.env")) {
            filter<org.apache.tools.ant.filters.FixCrLfFilter>("eol" to org.apache.tools.ant.filters.FixCrLfFilter.CrLf.newInstance("lf"))
        }
    }

    // The context goes to the runtime as one archive on standard input, not as a directory.
    // Every runtime takes it that way, and Podman on macOS cannot read a directory of several
    // hundred jars through its virtual machine's file sharing ("cannot allocate memory" while
    // listing extended attributes).
    val archive = tasks.register<Tar>("archiveImage$taskSuffix") {
        description = "Packs the build context of the image of $app."
        from(stage)
        archiveFileName = "$app.tar"
        destinationDirectory = layout.buildDirectory.dir("context")
        isPreserveFileTimestamps = false
        isReproducibleFileOrder = true
    }

    tasks.register<Exec>("buildImage$taskSuffix") {
        group = "build"
        description = "Builds the container image dhc/$app."
        val version = project.version.toString()
        val contextArchive = archive.flatMap { it.archiveFile }
        inputs.file(contextArchive).withPropertyName("context")
        inputs.property("baseImage", baseImage)
        doFirst {
            val runtime = containerRuntime()
            val arguments = mutableListOf(runtime, "build")
            if (runtime.endsWith("podman")) {
                // Podman's default image format drops HEALTHCHECK; docker's keeps it.
                arguments += listOf("--format", "docker")
            }
            arguments += listOf(
                "--build-arg", "BASE_IMAGE=$baseImage",
                "--build-arg", "APP=$app",
                "-t", "dhc/$app:$version",
                "-t", "dhc/$app:latest",
                "-", // the context: the archive on standard input, with its Dockerfile at the top
            )
            commandLine(arguments)
            standardInput = contextArchive.get().asFile.inputStream()
            logger.lifecycle("buildImage: ${arguments.joinToString(" ")} < ${contextArchive.get().asFile.name}")
        }
    }
}

tasks.register("buildImages") {
    group = "build"
    description = "Builds the container images of all four hosts. Needs docker or podman."
    dependsOn(imageTasks)
}
