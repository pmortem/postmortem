# Summary

![The summary tab](img/summary.png)

The Summary tab shows, from the PGN tags: the players (with ratings), the date, event, site, result and how the game ended. If the moves match one, the **opening** is named with its ECO code, for example `King's Pawn Game: Wayward Queen Attack (C20)`.

Under it, one column per side:
- **Accuracy**: how close the moves were to the engine's best, in percent. It is the Lichess accuracy: the same formulas and constants, so that the same evaluations give the same number.
- **ACPL**: average centipawn loss per move.
- **Brilliant**: sound sacrifices, marked `!!` (see below).
- **Inaccuracies, mistakes, blunders**: moves that lose a lot of winning chances. Lichess's thresholds: a drop of 10, 20 or 30 percentage points in win chance.

## Brilliant moves
A move is marked `!!` (in a bluish green) in the Moves tab, on the chart label and as a dot on the chart, and counted in the Summary. Neither Lichess nor chess.com publishes a rule for this, so this one is our own, and every part of it can be tuned. A move is brilliant when **all** of these hold:
1. **It is sound:** the mover's winning chances drop by at most 2 percentage points, so it is as good as the best move or nearly.
2. **It is a real sacrifice:** after the move the opponent can win at least a minor piece (3 pawns) by taking and retaking on one square, over and above what the move itself took. Plain trades and recaptures win nothing and never count. Pawn sacrifices don't count either.
3. **The game is still open:** the mover's winning chances before the move are between 25% and 90%. Giving up a rook when far ahead, or throwing a piece away when lost, is not brilliant.
4. **It was a choice:** the move is neither the only legal move nor an answer to a check.

A move that gets a mark of `?!`, `?` or `??` is never also brilliant. The same rule applies to the moves of the lines you play. The rule is a rough one: it only looks at what can be taken right after the move, not at what the sacrifice wins later.

At the bottom, small, are the engine and the settings the game was analysed with.

## Compared with Lichess
For the same evaluations the numbers are exactly Lichess's. Our evaluations come from our own repeatable search, so a single move can sit on the other side of a threshold (for one test game, one inaccuracy). This is search noise, not a formula difference ([#88](https://github.com/pmortem/postmortem/issues/88)).
