## What and why

Closes #

## Definition of Done
- [ ] It does what the issue's "how I'll know it works" says (or the PLAN.md step)
- [ ] `:core` changes have a test on a real sample; a bug fix has a regression test
- [ ] `lab-check` passes and CI (`ci`) is green
- [ ] UI changed: I read the light and dark screenshots
- [ ] New screen or flow: `scripts/lab/smoke.steps` covers it
- [ ] User-visible behaviour added or changed: the matching `docs/` page is updated in the same PR
- [ ] No new Android permission unless the issue names it and it is already in the host's allow list; no hardcoded values
- [ ] The PR title is a conventional commit
- [ ] After the merge: the phone test page (visible changes only), naming the app version on the phone; for `refactor:`, `chore:`, `test:`, `docs:`, `ci:` and `build:` PRs (and a `perf:` with no visible change) a "no phone test: smoke passed on build N" comment instead
