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
}

// The real engine for the tests: the Linux build of the same Stockfish release the phone runs.
// scripts/lab/prepare fetches it. Without it (and with -Ppostmortem.coreOnly=true) those tests skip.
@Suppress("UNCHECKED_CAST")
val stockfishLinux = rootProject.extra["stockfishLinux"] as Provider<File>
val coreOnly = providers.gradleProperty("postmortem.coreOnly").isPresent

tasks.test {
    val binary = stockfishLinux.orNull?.takeIf { it.canExecute() }
    systemProperty("postmortem.stockfish", binary?.path ?: "")
    systemProperty("postmortem.engineRequired", (!coreOnly).toString())
    // The slow pass (every fixture game through the engine) runs before a merge: -Ppostmortem.slow=true.
    systemProperty("postmortem.slow", providers.gradleProperty("postmortem.slow").isPresent.toString())
    inputs.property("stockfishPath", binary?.path ?: "")
}
