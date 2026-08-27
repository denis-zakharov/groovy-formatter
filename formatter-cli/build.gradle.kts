// CLI entry point (groovy-format).
plugins {
    application
}

dependencies {
    implementation(project(":formatter-print"))
    implementation(project(":formatter-parser"))
    implementation("info.picocli:picocli:4.7.6")
}

application {
    mainClass.set("dev.groovyfmt.cli.Main")
}
