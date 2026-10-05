// Versions also recorded in gradle/libs.versions.toml for reference; hardcoded here directly
// because Gradle's generated version-catalog accessors aren't reliably reachable from a
// `subprojects { }` configuration block.
val junitVersion = "5.11.0"

allprojects {
    group = "dev.groovyfmt"
    version = "0.1.0-SNAPSHOT"

    repositories {
        mavenCentral()
    }
}

subprojects {
    apply(plugin = "java")

    configure<JavaPluginExtension> {
        toolchain {
            languageVersion.set(JavaLanguageVersion.of(25))
        }
    }

    tasks.withType<Test> {
        useJUnitPlatform()
    }

    dependencies {
        add("testImplementation", platform("org.junit:junit-bom:$junitVersion"))
        add("testImplementation", "org.junit.jupiter:junit-jupiter")
        add("testRuntimeOnly", "org.junit.platform:junit-platform-launcher")
    }
}
