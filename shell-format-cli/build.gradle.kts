// CLI entry point (shell-format), standalone: formats shell (sh/bash) scripts. Deliberately has
// no dependency on any Groovy-formatter module — it's usable on its own for plain .sh files, not
// just Jenkinsfile sh '''...''' blocks.
plugins {
    application
    id("org.graalvm.buildtools.native") version "1.1.10"
}

dependencies {
    implementation(project(":formatter-shell"))
    implementation(project(":formatter-doc"))
    implementation("info.picocli:picocli:4.7.6")
    // See formatter-cli/build.gradle.kts for why this is needed: picocli reads @Command/@Option
    // annotations via reflection at startup, which native-image's closed-world analysis can't
    // see on its own without this codegen-generated reflect-config.
    annotationProcessor("info.picocli:picocli-codegen:4.7.6")
}

tasks.withType<JavaCompile> {
    options.compilerArgs.add("-Aproject=${project.group}/${project.name}")
}

application {
    mainClass.set("dev.groovyfmt.shellcli.Main")
}

graalvmNative {
    binaries {
        named("main") {
            imageName.set("shell-format")
            mainClass.set("dev.groovyfmt.shellcli.Main")
            buildArgs.add("--no-fallback")
        }
    }
}

// A single self-contained jar bundling shell-format and all its runtime dependencies, so an end
// user only needs to grab one file and a JVM: `java -jar shell-format-all.jar script.sh`.
val fatJar =
    tasks.register<Jar>("fatJar") {
        group = "distribution"
        description = "Builds a single executable jar with all runtime dependencies bundled in."
        archiveBaseName.set("shell-format")
        archiveClassifier.set("all")
        duplicatesStrategy = DuplicatesStrategy.EXCLUDE
        manifest {
            attributes["Main-Class"] = "dev.groovyfmt.shellcli.Main"
        }
        dependsOn(configurations.runtimeClasspath)
        from(sourceSets.main.get().output)
        from(configurations.runtimeClasspath.get().map { if (it.isDirectory) it else zipTree(it) })
    }

tasks.named("assemble") { dependsOn(fatJar) }
