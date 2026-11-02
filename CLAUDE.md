# Postmortem: notes for Claude

The workflow (loop, PRs, Definition of Done) is in the box's global `~/.claude/CLAUDE.md`; this file holds only what is specific to the code.

- Modules: `:core` (pure Kotlin, no Android) and `:app` (Compose). Anything in `:core` needs a test on a real sample from `core/src/test/resources`.
- Engine settings live in `AnalysisConfig`. UI text goes in `strings.xml`, visual values in the theme. No hardcoded values.
- Stockfish versions and sha256 pins are in `scripts/lab/stockfish.env`; `scripts/lab/prepare` fetches them into `$LAB_CACHE`.
- The app requests no Android permission. If one is needed, stop and ask the user.
- Smoke steps are data in `scripts/lab/smoke.steps`; give new screens a `testTag` in `UiTags.kt` and add them there.
- Check before a merge: `lab-check`, then CI (`ci`) green. Use `./gradlew assembleDebug test lint spotlessCheck` for a quick local run (`spotlessApply` fixes formatting).
