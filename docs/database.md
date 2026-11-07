# The database

Your analysed games are kept in one standard SQLite file in the app's own storage, `postmortem.sqlite` unless you rename it. The **Database** section of the Settings page (the **Settings** tab of the start page, or the cog on the result screen) lets you name it, export it, switch to another file, and merge one into it. None of this needs a permission, and nothing leaves the phone unless you share it.

## Database name
The text field shows the name of the active file. Type another and tap **Save name**: `club` and `club.sqlite` both give `club.sqlite`. Only letters, digits, `-`, `_` and spaces are allowed. The file is renamed (with its side files), and the Library is unchanged. If a database of that name already exists, nothing is renamed and the page says so. The name is remembered.

## Export
**Export** closes the database so the copy is whole, then opens Android's share sheet with a copy of the file: send it to OneDrive, Google Drive, Files, mail, or anything else. The copy opens on a PC or Mac in DB Browser for SQLite, `sqlite3` or DBeaver: the tables are `game` (one row per game, with its PGN and tags), `node` (every position as a tree: the game's moves and your lines) and `eval` (the evaluations). For a game analysed from now on, `eval.best_move` holds the engine's best move (UCI, such as `g1f3`) for every main-line position that has a legal move; games saved earlier have it empty, and it is filled in when something needs it. The best move is kept for the game's own moves only, not for the positions of your lines. Export, import and merge carry it along.

## Open a database
The list shows every `.sqlite` file in the app's database folder with its size and the number of games in it; the active one has a tick. Tap one to make it the active database: the Library and the automatic saving use it from then on. The game on screen belongs to the database you left, so it stops being saved.

**Browse…** opens Android's file picker, where you can pick a `.sqlite` file from Downloads, OneDrive, Drive and so on. It is copied into the folder under its own name (a number is added if that name is taken), checked, and opened.

## Import into this database
**Import into this database** opens the file picker again. The games in the picked file are added to the active database, with their lines and evaluations; a game that is already there (same moves, start position and tags) is skipped. The page says, for example, "3 added, 1 skipped". The picked file is not kept, and not changed.

## Safety
A picked file is checked before it is used: it must be a SQLite file, have the Postmortem tables, and not come from a newer version of the app. A file that fails the check shows a clear message and nothing is changed: the active database is never replaced or touched. A file from an older version is brought up to date on a copy.
