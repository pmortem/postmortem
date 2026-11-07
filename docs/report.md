# Report

The **Report** tab looks across every game saved in the active [database](database.md) for one player. Only games you have analysed count, and only the ones in the database that is open. It shows the player's openings and the wins they missed, and has a practice mode made from the player's own mistakes.

![The Report tab, with sample games](img/report.png)

## Picking a player
Type a name in the **Player** field, or tap one of the suggestions under it. The suggestions are the names in the White and Black tags of your saved games, the one with most games first, with the number of games in brackets, for example `Thomas (23)`.

- Names match without regard to capital letters, and spaces at the start and end are ignored.
- Games with no name (`?` or empty) are left out.
- The last name you picked is remembered, also after the app is closed.
- If the database has no games, the tab says **Analyse some games first**. If it has games but no names, or none for the name you typed, it says so.

## The overview
One line under the field: the number of games, how many were won, drawn and lost, and the average accuracy of the games that have one, for example `23 games · 11 W 4 D 8 L · avg accuracy 78 %`. The accuracy is the player's own, as shown in the [Summary](summary.md).

## Openings
Two sections, **As White** and **As Black**. Each row is an opening family: the name before the first colon of the opening's name, for example `Sicilian Defense`. A row shows the number of games, how they ended as percentages (W, D, L, with a small bar of green, grey and red), and the average accuracy. The rows are sorted by games, the most played first; games with no detected opening are listed last as **Unknown opening**.

- **Percentages** are counted over the finished games. A game whose result is `*` or missing counts in the number of games, but not in the percentages (a row with only such games says "Not finished").
- **Tap a family** to open its variations, with the same numbers. A family with a single variation has no list: tapping it goes straight to its games.
- **Tap a variation** to see its games: the opponent, the result, the date and the accuracy. Tap a game to open it as from the [Library](analysis.md#the-library). Back returns to the Report with the list where it was; Back once more leaves the Report tab for Analyse.

The numbers are worked out again each time the tab opens, so a game analysed or deleted since then is counted.

## Missed wins
Below the openings, a card about converting winning positions, the first of the "mistakes you keep making" insights.

- **A missed win** is a game of the player's where, after **two of their moves in a row**, they were clearly winning (winning chances of at least 50 % on the chart scale, about +3.0 pawns, or a forced mate for them), and which they then **drew or lost**. Games without a result are left out.
- The **turning move** is the player's move after the best moment of that win that lost the most winning chances. It normally has a `?` or `??` mark in the Moves tab too.
- **A missed mate** is a position where the player had a forced mate in at most **5 moves** and the move they played left no mate for them. It is listed even when the game was won, as on Lichess. A mate that was kept (even if it got longer) or delivered is not missed.

The card says `Missed wins: 4 of 19 decided games (21 %)` and `Missed mates: 3`, then lists the games newest first: the opponent, the date, your colour, the peak evaluation from your side (`+4.2`), the turning move with its number and mark (`31.Qxb7??`), the evaluation after it, and how the game ended. A missed mate says `had mate in 3, played 12.Qh5`. Tap a row to open the game at that move, as from the [Library](analysis.md#the-library); Back returns to the Report.

The numbers come from the evaluations stored with each game, so a game must have been analysed (every game in the Library has been). The thresholds (50 %, two moves, five moves) are named constants in the code and can be tuned.

## Learn from your mistakes
The **Learn from your mistakes** button, at the top of the Report, is a quiz made of your own games, like Lichess's "Learn from your mistakes" but across every game in the database. Under the button it says how many positions are still to go and how many you have solved, for example `12 to go · 5 solved`.

![A position to solve](img/drill.png)

**Which positions.** The position before each move of yours that was marked `?` (a mistake) or `??` (a blunder), where the marks are the ones of the [Moves tab](result-screen.md). Inaccuracies are left out. They come in this order: the turning moves of your missed wins and your missed mates first, then blunders, then mistakes, the newest game first within each. Solved positions leave the queue. Positions you failed or skipped come back after the others, the one tried longest ago first.

**The question.** The board shows the position from your side, with the opponent's last move marked, and says `Game vs Anna, move 31. You played Qxb7 (+4.2 → -1.3). Find a better move.` Play a move on the board as you would on the result screen (tap, or drag). While it is being checked a small spinner shows and nothing else can be touched. The answer is:
- **Right!** when it is the engine's best move for that position.
- **Good move, but there's a better one** when it is not, but your winning chances after it fall by less than an inaccuracy would (Lichess's rule, the same limit as the marks), so it is nearly as good. An arrow shows the best move, and the position counts as solved.
- **Not this one** otherwise. The move is taken back. **Try again** asks the same position again; **Show** shows the best move with an arrow and its evaluation and counts the position as failed for now.

**Always there:** **Skip** (counts as a try that is not solved), **Next** after an answer, **Open game** (opens the game on the result screen at that move, and ends the practice) and the back arrow. At the end of the queue it says `All done: 3 solved, 2 to try again`, with **Start over with the failed ones**.

**The engine.** One engine process at most. A move other than the stored best move is checked with the same settings as the game's analysis (fixed nodes, one thread), so a position has one value whenever it is checked. Games analysed before best moves were kept get theirs when a position of theirs is first asked, and keep it. The live arrows are off here.

**Progress** is stored in the database file (the `drill` table, see [the database](database.md)): it survives restarts, goes with the file when you export it, and is merged with it when you import one. Deleting a game deletes its progress.
