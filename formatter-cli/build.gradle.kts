// CLI entry point (groovy-format).
plugins {
    application
    id("org.graalvm.buildtools.native") version "1.1.10"
}

dependencies {
    implementation(project(":formatter-print"))
    implementation(project(":formatter-parser"))
    implementation("info.picocli:picocli:4.7.6")
    // Generates GraalVM reflect-config for the @Command/@Option-annotated classes at compile
    // time, since picocli reads those annotations via reflection at startup and native-image's
    // closed-world analysis can't see that on its own.
    annotationProcessor("info.picocli:picocli-codegen:4.7.6")
}

tasks.withType<JavaCompile> {
    options.compilerArgs.add("-Aproject=${project.group}/${project.name}")
}

application {
    mainClass.set("dev.groovyfmt.cli.Main")
}

graalvmNative {
    binaries {
        named("main") {
            imageName.set("groovy-format")
            mainClass.set("dev.groovyfmt.cli.Main")
            buildArgs.add("--no-fallback")
        }
    }
}

// A single self-contained jar bundling groovy-format and all its runtime dependencies, so an
// end user only needs to grab one file and a JVM: `java -jar groovy-format-all.jar file.groovy`.
val fatJar =
    tasks.register<Jar>("fatJar") {
        group = "distribution"
        description = "Builds a single executable jar with all runtime dependencies bundled in."
        archiveBaseName.set("groovy-format")
        archiveClassifier.set("all")
        duplicatesStrategy = DuplicatesStrategy.EXCLUDE
        manifest {
            attributes["Main-Class"] = "dev.groovyfmt.cli.Main"
        }
        dependsOn(configurations.runtimeClasspath)
        from(sourceSets.main.get().output)
        from(configurations.runtimeClasspath.get().map { if (it.isDirectory) it else zipTree(it) })
    }

tasks.named("assemble") { dependsOn(fatJar) }
