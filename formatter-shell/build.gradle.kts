// Standalone shell (sh/bash) formatter: hand-written lexer/parser/printer (see AGENTS.md — there
// is no off-the-shelf shell grammar to reuse the way formatter-parser reuses Groovy's own ANTLR
// grammar) on top of formatter-doc's Wadler/Prettier-style Doc IR. Public API:
// dev.groovyfmt.shell.ShellFormatter.format(String).
dependencies {
    implementation(project(":formatter-doc"))
}
