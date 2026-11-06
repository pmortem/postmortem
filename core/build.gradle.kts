plugins {
    alias(libs.plugins.kotlin.jvm)
}

val javaVersion = providers.gradleProperty("postmortem.javaVersion").get().toInt()

java {
    sourceCompatibility = JavaVersion.toVersion(javaVersion)
    targetCompatibility = JavaVersion.toVersion(javaVersion)
}

kotlin {
    compilerOptions {
        jvmTarget.set(
            org.jetbrains.kotlin.gradle.dsl.JvmTarget
                .fromTarget(javaVersion.toString()),
        )
    }
}

dependencies {
    implementation(libs.chesslib)
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlinx.coroutines.test)
    // A real SQLite for the store tests; the phone uses the one in Android.
    testImplementation(libs.sqlite.jdbc)
}

// The real engine for the tests: the Linux build of the same Stockfish release the phone runs.
// scripts/lab/prepare fetches it. Without it (and with -Ppostmortem.coreOnly=true) those tests skip.
@Suppress("UNCHECKED_CAST")
val stockfishLinux = rootProject.extra["stockfishLinux"] as Provider<File>
val coreOnly = providers.gradleProperty("postmortem.coreOnly").isPresent

tasks.test {
    // A failing test says why in the log, so a flake seen only in CI can be read.
    testLogging.exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    val binary = stockfishLinux.orNull?.takeIf { it.canExecute() }
    systemProperty("postmortem.stockfish", binary?.path ?: "")
    systemProperty("postmortem.engineRequired", (!coreOnly).toString())
    // The slow pass (every fixture game through the engine) runs before a merge: -Ppostmortem.slow=true.
    systemProperty("postmortem.slow", providers.gradleProperty("postmortem.slow").isPresent.toString())
    inputs.property("stockfishPath", binary?.path ?: "")
}
