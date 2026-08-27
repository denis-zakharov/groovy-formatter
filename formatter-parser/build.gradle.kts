// Adapter over Groovy's own "Parrot" ANTLR4 parser. This is the ONLY module allowed to import
// groovyjarjarantlr4.v4.runtime.* / org.apache.groovy.parser.antlr4.* directly, so a future
// Groovy version bump (or a change to how it shades its ANTLR runtime) has a one-module blast
// radius.
dependencies {
    implementation("org.apache.groovy:groovy:4.0.29")
    // Deliberately NOT adding org.antlr:antlr4-runtime: Groovy relocates its own copy of the
    // ANTLR4 runtime to groovyjarjarantlr4.v4.runtime.* inside its jar. A second, real ANTLR
    // dependency on the classpath would introduce a duplicate, incompatible type hierarchy.
}
