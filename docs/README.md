# Postmortem documentation

Postmortem analyses a chess game on your phone: paste a PGN, Stockfish checks every position, and you step through the game with the board, an evaluation for each move and a summary of how each side played. Everything runs on the device and the app asks for no Android permission.

## Finding your way
The start page has four tabs along the bottom:

| Tab | What it is |
| --- | --- |
| **Analyse** | Paste a game and analyse it ([Paste and analyse](analysis.md)) |
| **Library** | Your saved games ([The Library](analysis.md#the-library)) |
| **Report** | How you do across your saved games ([Report](report.md)) |
| **Settings** | The database ([The database](database.md)) |

Back from another tab goes to Analyse; Back on Analyse leaves the app. The bar is only on the start page, not while a game is being analysed or shown, and it hides while the keyboard is open. On the result screen the cog next to the flip button opens Settings over the game.

| Page | What is in it |
| --- | --- |
| [Paste and analyse](analysis.md) | Pasting a game, the progress ring, what is checked, errors |
| [The result screen](result-screen.md) | Board, last-move tint, eval bar (and RIP), arrows, flip, Moves / Chart / Summary tabs |
| [Report](report.md) | Pick a player, see their openings as White and Black with win/draw/loss percentages |
| [Background analysis](background-analysis.md) | The analysis queue, the notification, gentle and full speed, the heat pause |
| [The database](database.md) | Where the games are saved, naming it, export, switching files, import |
| [Summary](summary.md) | Overview, opening, accuracy, ACPL, inaccuracies, mistakes and blunders |
| [Engine](engine.md) | Stockfish, the repeatable analysis settings, live arrows, the long-press readout |
| [Testing](testing.md) | The smoke run, phone test pages, how bugs are reported |
| [Credits and licences](credits-and-licences.md) | Licences of the app, the engine, the pieces and the data |

These pages describe what the app does today. Each pull request that adds or changes something you can see updates the matching page.
