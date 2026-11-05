# Paste and analyse

![The paste screen](img/paste.png)

## Pasting a game
1. Open the app. If your clipboard holds a PGN, it is filled in for you; otherwise paste one into the box (from Lichess or Chess.com).
2. Tap **Analyse**.

The version of the app is shown small on this screen.

Only standard chess is supported, and only the first game of the text is read. Comments, variations, move numbers and clock tags are ignored. A game may start from a position given in a `FEN` tag.

## When a game is refused
The app says why under the box, and nothing is analysed:
- **Paste a game first.** The box is empty.
- **This game has no moves.**
- **The starting position is not valid.**
- **Not supported.** A variant such as Chess960 or Crazyhouse; only standard chess is.
- **Illegal move N (ply M).** The game contains a move that is not legal.
- **The engine stopped before it finished. Try again.** The engine broke during the analysis.

## While it analyses
![The progress ring](img/analysing.png)

A ring fills with the percentage done, and under it a count such as `13/27` shows how many positions are finished. The percentage never goes backwards. **Back** stops the analysis and returns to the pasted game.

Every position of the game is checked (one more than the number of half-moves, because the start counts). A 27-position game takes about 20 seconds on the test phone, an 80-move game about 100 seconds.

How the engine is set up is on the [engine page](engine.md).
