# Testing

## Smoke run
Every build is installed on a real phone by the lab, where a scripted run (`scripts/lab/smoke.steps`) pastes a game, analyses it, steps, opens each tab, flips the board and toggles the arrows, in light and dark mode, taking screenshots. The screenshots in this folder come from it, always the dark-mode ones.

## Phone test pages
After each merged change an issue titled `Phone test: <change>` lists the steps to try by hand on the phone, with checkboxes. Tick them in the issue; comment what you see. Found a bug? Use the **Report a bug** link on the page.

## Reporting a bug
Open the bug form in the issues tab. A bug gets a regression test with its fix.
