plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
}

// The pinned Stockfish release lives in scripts/lab/stockfish.env, which scripts/lab/prepare also reads.
// prepare puts the binaries in $LAB_CACHE/stockfish/<tag>/<asset>/<asset>.
val stockfishPins: Map<String, String> = file("scripts/lab/stockfish.env").readLines()
    .filter { '=' in it && !it.trimStart().startsWith("#") }
    .associate { it.substringBefore('=') to it.substringAfter('=') }

fun stockfishBinary(assetKey: String): Provider<File> = providers.environmentVariable("LAB_CACHE").map { cache ->
    val asset = stockfishPins.getValue(assetKey)
    File(cache, "stockfish/${stockfishPins.getValue("STOCKFISH_TAG")}/$asset/$asset")
}

extra["stockfishLinux"] = stockfishBinary("STOCKFISH_LINUX_ASSET")
extra["stockfishAndroid"] = stockfishBinary("STOCKFISH_ANDROID_ASSET")
