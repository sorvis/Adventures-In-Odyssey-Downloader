// JaCoCo task wiring for the Android unit-test suite.
//
// Why this lives in its own file: keeping the report-aggregation task
// definitions out of build.gradle.kts.main makes them easier to find
// during the next coverage-gate audit, and lets us drop the file if
// we ever migrate to a different reporter without surgery on the
// primary build file.
//
// What it does: registers `jacocoDebugReport` against the
// `testDebugUnitTest` task and aggregates results into a single
// HTML + XML report at `app/build/reports/jacoco/jacocoDebugReport/`.
// scripts/run-jvm-tests.sh and the pre-push hook read the XML to
// compute the line-coverage % and compare against the baseline.
//
// The classfile scope EXCLUDES generated code (Hilt, Room, KSP, R$*,
// BuildConfig) so the headline number reflects code we actually wrote
// and can test. Inclusions cover every package under com/odyssey/.

tasks.register<JacocoReport>("jacocoDebugReport") {
    group = "verification"
    description = "Aggregates JaCoCo coverage for testDebugUnitTest into HTML + XML reports."

    dependsOn("testDebugUnitTest")

    reports {
        xml.required.set(true)
        html.required.set(true)
    }

    val excludes = listOf(
        // Generated code — Hilt
        "**/Hilt_*.*", "**/*_Factory.*", "**/*_HiltModules*.*",
        "**/*_MembersInjector.*", "**/*_Provide*Factory*.*",
        "**/*_AssistedFactory*.*", "**/Dagger*.*",
        // Generated code — Room
        "**/*_Impl.*", "**/*_Impl\$*.*",
        // Generated code — Compose
        "**/ComposableSingletons\$*.*", "**/*\$Composable*.*",
        // Generated code — Android
        "**/R.class", "**/R\$*.class", "**/BuildConfig.*",
        "**/Manifest*.*", "**/*Test*.*",
        // Anonymous lambda classes — JaCoCo can't see them properly;
        // their parent class's coverage stays intact.
        "**/*\$Lambda*.*", "**/*\$WhenMappings.*",
    )

    val classDirs = layout.buildDirectory.dir("tmp/kotlin-classes/debug").map {
        fileTree(it) { exclude(excludes) }
    }
    val javaClassDirs = layout.buildDirectory.dir("intermediates/javac/debug/classes").map {
        fileTree(it) { exclude(excludes) }
    }
    classDirectories.setFrom(classDirs, javaClassDirs)

    sourceDirectories.setFrom(files("src/main/java", "src/main/kotlin"))

    executionData.setFrom(
        layout.buildDirectory.file("jacoco/testDebugUnitTest.exec"),
    )
}
