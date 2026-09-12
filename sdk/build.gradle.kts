import com.vanniktech.maven.publish.JavaLibrary
import com.vanniktech.maven.publish.JavadocJar
import com.vanniktech.maven.publish.SonatypeHost
import java.lang.module.ModuleDescriptor
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

// No Automatic-Module-Name: src/main/java/module-info.java declares a real
// module of the same name, and an explicit descriptor makes the manifest
// attribute dead weight -- the JVM ignores it once module-info.class is present.
// Unlike the attribute, the descriptor actually stops consumers reaching
// xyz.tesser.sdk.java.internal.*.

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
                // module-info.class has ACC_MODULE set and is not loadable by
                // Class.forName. It is still API -- dropping an `exports` breaks
                // consumers -- so it is rendered from its descriptor below instead.
                .filterNot { it == "module-info" }
                .filterNot { name -> ignored.any { name == it || name.startsWith("$it.") } }
                .sorted()

        val out = StringBuilder()
        out.append(renderModuleDescriptor(roots))
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

    /**
     * Renders the JPMS descriptor, so an accidental change to `exports` or
     * `requires` shows up as an API diff like any other. Empty when the project
     * has no module-info.
     *
     * Qualified targets are rendered, not just the package name: narrowing
     * `exports foo` to `exports foo to bar` breaks every consumer except `bar`,
     * and without the `to` clause the two are indistinguishable in the lockfile.
     * `targets()` is empty for an unqualified directive, so those render unchanged.
     *
     * `provides` is included because a consumer can bind to it through
     * ServiceLoader. `uses` is not: it declares what this module consumes, which
     * is an implementation detail rather than part of the contract offered out.
     */
    private fun renderModuleDescriptor(roots: List<File>): String {
        val file = roots.map { File(it, "module-info.class") }.firstOrNull { it.isFile }
            ?: return ""
        val descriptor: ModuleDescriptor = file.inputStream().use { ModuleDescriptor.read(it) }

        fun qualified(directive: String, source: String, targets: Set<String>) =
            if (targets.isEmpty()) {
                "$directive $source"
            } else {
                "$directive $source to ${targets.sorted().joinToString(", ")}"
            }

        val lines = mutableListOf<String>()
        descriptor.requires().mapTo(lines) { req ->
            val mods = req.modifiers().map { it.name.lowercase() }.sorted()
            val prefix = if (mods.isEmpty()) "" else mods.joinToString(" ", postfix = " ")
            "requires $prefix${req.name()}"
        }
        descriptor.exports().mapTo(lines) { qualified("exports", it.source(), it.targets()) }
        descriptor.opens().mapTo(lines) { qualified("opens", it.source(), it.targets()) }
        descriptor.provides().mapTo(lines) { prov ->
            "provides ${prov.service()} with ${prov.providers().sorted().joinToString(", ")}"
        }
        lines.sort()

        return buildString {
            append("module ").append(descriptor.name()).append(" {\n")
            lines.forEach { append("    ").append(it).append("\n") }
            append("}\n\n")
        }
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
    // JavadocJar.None, not the plugin's default of JavadocJar.Javadoc.
    //
    // The `java` block above calls withJavadocJar(), which adds Gradle's own
    // `javadocJar` (classifier `javadoc`) to the java component -- and wires it
    // into `assemble`, so `:sdk:build` in CI actually compiles the javadoc.
    // The plugin's default would register a SECOND javadoc jar
    // (`mavenPlainJavadocJar`) on the same publication. Two artifacts sharing an
    // extension and classifier make the publication invalid, and
    // `publishMavenPublicationToMavenCentralRepository` fails validation with
    // "multiple artifacts with the identical extension and classifier". The
    // sources jar does not collide because the plugin routes that through
    // withSourcesJar(), which is idempotent.
    configure(JavaLibrary(javadocJar = JavadocJar.None(), sourcesJar = true))

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
