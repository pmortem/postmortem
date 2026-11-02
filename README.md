# Postmortem

An Android app that analyses a chess game on your phone. Paste a PGN (from Lichess or Chess.com), Stockfish checks every position, and you step through the game with the board and an evaluation for each move.

Everything runs on the device. The app asks for no Android permission, so it cannot use the network.

## What it does
1. Paste a game, or open the app with a PGN on the clipboard.
2. Tap Analyse. A progress page shows each position as Stockfish works through it.
3. Step through the moves on the board. White ahead is positive; a forced mate shows as `M3`.

## Layout
- `core/`: pure Kotlin/JVM. PGN parser, board layout, UCI engine client and the analyser. Tested on real Lichess and Chess.com games and on the real Stockfish binary.
- `app/`: the Compose UI. Stockfish ships as `libstockfish.so` so Android lets it run from the native library directory.
- `scripts/lab/`: the build-and-test loop (`prepare`, `smoke.steps`, `smoke.pgn`) used by the lab that builds and installs the app on a phone.

## Build
Needs JDK 21 and the Android SDK (compileSdk and build-tools versions are in `gradle.properties`).

```sh
scripts/lab/prepare                      # downloads the pinned Stockfish binaries into $LAB_CACHE
./gradlew assembleDebug test lint spotlessCheck  # the same as CI (spotlessApply fixes formatting)
./gradlew test -Ppostmortem.slow=true    # also analyses every fixture game with the real engine
```

Stockfish is pinned by tag and source sha256 in `scripts/lab/stockfish.env`. The phone's engine is built from that source with the Android NDK (`scripts/lab/build-stockfish`, 16 KB page alignment checked), cached in `$LAB_CACHE`; the first build needs the NDK from `stockfish.env` (`sdkmanager --install "ndk;<version>"`) and about 5 minutes.

## Licence
GPLv3, see `LICENSE`. Stockfish is GPLv3: the exact source is the sf_19 tag, <https://github.com/official-stockfish/Stockfish/releases/tag/sf_19>, and the phone build recipe is `scripts/lab/build-stockfish`. Chess piece art and chesslib are credited in `THIRD_PARTY_NOTICES.md`.
