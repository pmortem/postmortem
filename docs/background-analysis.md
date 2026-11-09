# Background analysis

An analysis no longer stops when you leave the app. **Analyse** puts the game in a **queue**, which goes on while you do something else, such as playing your next game on Lichess or chess.com. Games are analysed one at a time, in the order they were added.

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
