import com.vanniktech.maven.publish.SonatypeHost
import java.lang.reflect.Modifier
import java.net.URLClassLoader

plugins {
    `java-library`
    alias(libs.plugins.spotless)
    alias(libs.plugins.maven.publish)
}

java {
    toolchain { languageVersion.set(JavaLanguageVersion.of(17)) }
    withSourcesJar()
    withJavadocJar()
}

dependencies {
    implementation(libs.jackson.databind)
    implementation(libs.bouncycastle)
    implementation(libs.slf4j.api)

    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
    testImplementation(libs.assertj)
    testImplementation(libs.mockito.core)
    testImplementation(libs.mockito.junit)
    testRuntimeOnly(libs.slf4j.simple)
}

spotless {
    java {
        googleJavaFormat().aosp() // 4-space indent, matching sdk-kotlin's style
        target("src/**/*.java")
    }
}

tasks.test {
    useJUnitPlatform()
}

tasks.jar {
    manifest {
        attributes("Automatic-Module-Name" to "xyz.tesser.sdk.java")
    }
}

// ---------------------------------------------------------------------------
// Binary-compatibility gate
//
// sdk-kotlin uses the Kotlin binary-compatibility-validator. That plugin only
// registers its apiDump/apiCheck tasks for Kotlin compilations -- applied to a
// java-library project it silently contributes nothing, which was verified here
// before dropping it (`:sdk:tasks --all` listed no api* tasks at all).
//
// The plan's named fallback was japicmp, comparing against the previously
// published artifact. That does not work for a first release: with no published
// 0.0.1 there is no baseline, so it would be configured to no-op and would gate
// nothing until 0.0.2 -- and it provides neither an `apiCheck` task nor an
// `api/sdk-java.api` lockfile, both of which the verification command, CI, the
// release workflow and CONTRIBUTING all name.
//
// So the lockfile model is kept and implemented directly: apiDump writes the
// public API to api/sdk-java.api, apiCheck fails if the committed file has
// drifted. Unlike a baseline diff this works from the first release, and it
// catches an accidental public-API change in review rather than at publish time.
// ---------------------------------------------------------------------------

/** Packages that are `public` only because Java has no `internal` keyword. */
val internalPackages = listOf("xyz.tesser.sdk.java.internal")

abstract class ApiSignatureTask : DefaultTask() {

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val classesDirs: ConfigurableFileCollection

    @get:Classpath
    abstract val analysisClasspath: ConfigurableFileCollection

    @get:Input
    abstract val ignoredPackages: ListProperty<String>

    @get:OutputFile
    abstract val apiFile: RegularFileProperty

    /**
     * Renders every public and protected type and member reachable by a
     * consumer, sorted so the output depends on the API and not on compilation
     * order. Classes are loaded with initialization disabled -- this must not
     * run static initializers.
     */
    protected fun renderApi(): String {
        val roots = classesDirs.files.filter { it.isDirectory }
        val urls = (roots + analysisClasspath.files).map { it.toURI().toURL() }.toTypedArray()
        val loader = URLClassLoader(urls, ClassLoader.getPlatformClassLoader())
        val ignored = ignoredPackages.get()

        val binaryNames =
            roots
                .flatMap { root ->
                    root.walkTopDown()
                        .filter { it.isFile && it.name.endsWith(".class") }
                        .map {
                            it.relativeTo(root).path
                                .removeSuffix(".class")
                                .replace(File.separatorChar, '.')
                        }
                        .toList()
                }
                .filterNot { name -> ignored.any { name == it || name.startsWith("$it.") } }
                .sorted()

        val out = StringBuilder()
        try {
            for (name in binaryNames) {
                val cls = Class.forName(name, false, loader)
                if (cls.isSynthetic) continue
                if (!Modifier.isPublic(cls.modifiers) && !Modifier.isProtected(cls.modifiers)) {
                    continue
                }
                out.append(describe(cls))
            }
        } finally {
            loader.close()
        }
        return out.toString()
    }

    private fun describe(cls: Class<*>): String {
        val body = mutableListOf<String>()

        cls.genericSuperclass?.let { body.add("extends ${it.typeName}") }
        cls.genericInterfaces.map { "implements ${it.typeName}" }.sorted().forEach { body.add(it) }
        cls.permittedSubclasses
            ?.map { "permits ${it.name}" }
            ?.sorted()
            ?.forEach { body.add(it) }

        val members = mutableListOf<String>()
        cls.declaredFields
            .filter { visible(it.modifiers) && !it.isSynthetic }
            .mapTo(members) { it.toGenericString() }
        cls.declaredConstructors
            .filter { visible(it.modifiers) && !it.isSynthetic }
            .mapTo(members) { it.toGenericString() }
        cls.declaredMethods
            .filter { visible(it.modifiers) && !it.isSynthetic && !it.isBridge }
            .mapTo(members) { it.toGenericString() }
        members.sort()
        body.addAll(members)

        return buildString {
            append(cls.toGenericString()).append(" {\n")
            body.forEach { append("    ").append(it).append("\n") }
            append("}\n\n")
        }
    }

    private fun visible(modifiers: Int) =
        Modifier.isPublic(modifiers) || Modifier.isProtected(modifiers)
}

abstract class ApiDumpTask : ApiSignatureTask() {
    @TaskAction
    fun dump() {
        val target = apiFile.get().asFile
        target.parentFile.mkdirs()
        target.writeText(renderApi())
        logger.lifecycle("Wrote ${target.path}")
    }
}

abstract class ApiCheckTask : ApiSignatureTask() {
    @TaskAction
    fun check() {
        val expectedFile = apiFile.get().asFile
        if (!expectedFile.isFile) {
            throw GradleException(
                "No API lockfile at ${expectedFile.path}. Run `./gradlew :sdk:apiDump` and commit it.",
            )
        }
        val actual = renderApi()
        val expected = expectedFile.readText()
        if (actual != expected) {
            val diff = firstDifference(expected, actual)
            throw GradleException(
                "Public API differs from ${expectedFile.path}.\n" +
                    diff +
                    "\nIf the change is intended, run `./gradlew :sdk:apiDump` and commit the result.",
            )
        }
    }

    private fun firstDifference(expected: String, actual: String): String {
        val expectedLines = expected.lines()
        val actualLines = actual.lines()
        val removed = expectedLines.filterNot { it in actualLines }.filter { it.isNotBlank() }
        val added = actualLines.filterNot { it in expectedLines }.filter { it.isNotBlank() }
        return buildString {
            removed.take(20).forEach { append("  - ").append(it.trim()).append("\n") }
            added.take(20).forEach { append("  + ").append(it.trim()).append("\n") }
        }
    }
}

val apiDump by tasks.registering(ApiDumpTask::class) {
    group = "verification"
    description = "Writes the public API of :sdk to api/sdk-java.api."
    classesDirs.from(sourceSets.main.get().output.classesDirs)
    analysisClasspath.from(configurations.named("compileClasspath"))
    ignoredPackages.set(internalPackages)
    apiFile.set(layout.projectDirectory.file("api/sdk-java.api"))
    dependsOn(tasks.named("classes"))
}

val apiCheck by tasks.registering(ApiCheckTask::class) {
    group = "verification"
    description = "Fails if the public API of :sdk has drifted from api/sdk-java.api."
    classesDirs.from(sourceSets.main.get().output.classesDirs)
    analysisClasspath.from(configurations.named("compileClasspath"))
    ignoredPackages.set(internalPackages)
    apiFile.set(layout.projectDirectory.file("api/sdk-java.api"))
    dependsOn(tasks.named("classes"))
}

tasks.named("check") { dependsOn(apiCheck) }

mavenPublishing {
    publishToMavenCentral(SonatypeHost.CENTRAL_PORTAL)
    signAllPublications()
    coordinates(group.toString(), "sdk-java", version.toString())

    pom {
        name.set("Tesser Java SDK")
        description.set(
            "Java SDK for the Tesser API. Produces locally-signed wallet-creation and rebalance-step payloads.",
        )
        inceptionYear.set("2026")
        url.set("https://github.com/tesser-payments/sdk-java")
        licenses {
            license {
                name.set("The Apache License, Version 2.0")
                url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                distribution.set("repo")
            }
        }
        developers {
            developer {
                id.set("tesser-payments")
                name.set("Tesser")
                url.set("https://tesser.xyz")
            }
        }
        scm {
            url.set("https://github.com/tesser-payments/sdk-java")
            connection.set("scm:git:https://github.com/tesser-payments/sdk-java.git")
            developerConnection.set("scm:git:ssh://git@github.com/tesser-payments/sdk-java.git")
        }
    }
}
