// Adapter over Groovy's own "Parrot" ANTLR4 parser. This is the ONLY module allowed to import
// the shaded ANTLR4 runtime (groovyjarjarantlr4.v4.runtime.*) directly, so a future Groovy
// version bump (or a change to how it shades its ANTLR runtime) has a one-module blast radius.
// Downstream modules (formatter-print, formatter-comments) DO walk the actual CST node types
// (org.apache.groovy.parser.antlr4.GroovyParser$*Context) directly — there are ~200 of them and
// they ARE the parse tree, so wrapping them all in adapter types would just reimplement the CST
// as a parallel hierarchy for no real benefit. `api` (not `implementation`) so those types are
// visible on downstream modules' compile classpath.
plugins {
    `java-library`
}

dependencies {
    api("org.apache.groovy:groovy:4.0.29")
    // Deliberately NOT adding org.antlr:antlr4-runtime: Groovy relocates its own copy of the
    // ANTLR4 runtime to groovyjarjarantlr4.v4.runtime.* inside its jar. A second, real ANTLR
    // dependency on the classpath would introduce a duplicate, incompatible type hierarchy.
}
