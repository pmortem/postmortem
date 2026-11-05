# Postmortem documentation

Postmortem analyses a chess game on your phone: paste a PGN, Stockfish checks every position, and you step through the game with the board, an evaluation for each move and a summary of how each side played. Everything runs on the device and the app asks for no Android permission.

| Page | What is in it |
| --- | --- |
| [Paste and analyse](analysis.md) | Pasting a game, the progress ring, what is checked, errors |
| [The result screen](result-screen.md) | Board, last-move tint, eval bar (and RIP), arrows, flip, Moves / Chart / Summary tabs |
| [Summary](summary.md) | Overview, opening, accuracy, ACPL, inaccuracies, mistakes and blunders |
| [Engine](engine.md) | Stockfish, the repeatable analysis settings, live arrows, the long-press readout |
| [Testing](testing.md) | The smoke run, phone test pages, how bugs are reported |
| [Credits and licences](credits-and-licences.md) | Licences of the app, the engine, the pieces and the data |

These pages describe what the app does today. Each pull request that adds or changes something you can see updates the matching page.
