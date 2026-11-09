# Background analysis

An analysis no longer stops when you leave the app. **Analyse** puts the game in a **queue**, which goes on while you do something else, such as playing your next game on Lichess or chess.com. Games are analysed one at a time, in the order they were added.

## Send a game from Lichess or chess.com
Postmortem is in Android's **share sheet** for chess games. Right after a game, share its **PGN** and pick **Postmortem**: a short message shows, you are back in the chess app at once, and the game is analysed in the background while you start the next one.

- **Lichess:** on the finished game, open the menu, choose *Share* and then the PGN (not the link), and pick Postmortem.
- **chess.com:** on the finished game, use *Share*, choose the PGN (or download the `.pgn` file and share it), and pick Postmortem.

(The exact names of the buttons differ between app versions.)

What the message says:

| You shared | The message | What happens |
| --- | --- | --- |
| a game | `Queued: Anna vs Ben (2 waiting)` | it is added to the queue |
| a game that is saved already | `Already in your Library` | nothing is queued; open it from the Library |
| a game that is queued already | `Already in the queue` | it keeps its place |
| a PGN with several games, or several `.pgn` files | `Queued 3 games` | each is queued, at most 20 from one share |
| a link to the game | `Share the PGN, not the link` | nothing: Postmortem has no internet access |
| anything else | `That doesn't look like a chess game` | nothing |

Postmortem never watches the clipboard: Android does not let an app read it from the background. The first share may ask whether Postmortem may show notifications.

## The notification
While the queue works, a notification shows the game being analysed, how far it is and how many games wait, for example `Analysing Anna vs Ben · 34 % · 2 waiting`. Its **Cancel** button takes the running game out of the queue. The notification goes away when the queue is empty.

When a game is done, a notification says `Analysed: Anna vs Ben`, with both accuracies; a tap opens it. A game that could not be analysed gets its own notification, with the reason.

## In the app
- If you stay on the analysing screen, the game opens when it is done, as before. **Back** returns to the paste page, and the game goes on in the queue.
- The **Library** shows the queued games at the top: the one being analysed with its progress, the others as waiting. The ✕ button cancels one. A finished game becomes a normal Library row; it was saved, as every analysed game is.
- A game that is already saved is not analysed again; it opens instead, as before.

## Gentle and full speed
- With Postmortem on screen the analysis runs at full speed, as before (one engine per core, at most four, one core left for the phone).
- In the background it uses **2 engines**, so the game you are playing stays smooth.
- The number is chosen when a game starts. The evaluations are the same either way: each position is searched with the same fixed settings whatever the number of engines.

## Heat
When the phone gets too hot (Android's thermal status is *severe* or worse), the queue pauses and the notification says **Paused: phone is hot**. A game that was halfway starts again from its first position when the phone has cooled down.

## When the app is closed or killed
The queue is kept in the database (the `queued` table, see [the database](database.md)). If Android or you close the app in the middle, the queue carries on when the app is opened again. The game that was halfway through starts again from its first position.

Android limits how long a data sync service may run each day. If the limit is reached, the queue waits and a notification says so. Opening Postmortem lets it go on.

## Notifications
Postmortem asks for permission to show notifications once, and only when you come back to the app after a game was analysed in the background, never when it starts. If you say no, the analysis still runs and finishes, and the Library shows the result; you just get no notifications.

## Permissions
These are the first permissions Postmortem asks for, all three for this feature:
- **Foreground service** and **foreground service (data sync)**, so Android lets the analysis run with the app in the background.
- **Notifications**, for the progress and the results.

Sharing a game needs no permission: the shared text or file is handed to Postmortem by Android.
