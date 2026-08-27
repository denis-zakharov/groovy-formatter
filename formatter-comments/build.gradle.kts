// Comment and blank-line extraction/attachment: the hardest, most bug-prone part of the
// formatter. Kept isolated so it can be unit tested against token streams directly.
dependencies {
    implementation(project(":formatter-parser"))
}
