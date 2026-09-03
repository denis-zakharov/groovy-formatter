// CST -> Doc printer: the per-node-type visitors that walk Groovy's parse tree and emit Doc trees.
dependencies {
    implementation(project(":formatter-doc"))
    implementation(project(":formatter-parser"))
    implementation(project(":formatter-comments"))
    implementation(project(":formatter-shell"))
}
