plugins {
    id("com.android.application") version "8.7.2" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "2.0.21" apply false
    id("com.google.devtools.ksp") version "2.0.21-1.0.27" apply false
    id("com.google.dagger.hilt.android") version "2.52" apply false
    id("io.gitlab.arturbosch.detekt") version "1.23.7"
}

// detekt — Kotlin static analysis. The rule we care about most is
// `exceptions>SwallowedException` (catch/getOrElse that discards the
// throwable without logging) — that's exactly the class of bug that
// hid YSH download failures behind silent retries. Existing
// violations are grandfathered into detekt-baseline.xml; new
// violations fail the build via the `check` task hook below.
detekt {
    source.setFrom(files("app/src/main/java", "app/src/test/java", "app/src/androidTest/java"))
    config.setFrom(files("$rootDir/detekt.yml"))
    baseline = file("$rootDir/detekt-baseline.xml")
    buildUponDefaultConfig = true
    autoCorrect = false
    parallel = true
}

// The `check` lifecycle task lives on subprojects (added by the Java
// plugin), not on the root. Wire :app:check → :detekt so a normal
// `./gradlew :app:check` (which release.sh implicitly runs through
// `:app:test` etc.) also runs the linter.
project(":app").afterEvaluate {
    tasks.named("check") {
        dependsOn(rootProject.tasks.named("detekt"))
    }
}
