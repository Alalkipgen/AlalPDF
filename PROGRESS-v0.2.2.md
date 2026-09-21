# v0.2.2 Fix Progress

Branch: `fix/v0.2.2-scroll-links-selection`
Base: `origin/main` at `b07007d`
Backup branch: `backup/pre-v0.2.2-20260920-233226`
Untracked schema backup: `/data/alalpdf-backups/app-schemas-pre-v0.2.2.tgz`

## Resume
1. `git fetch origin`
2. `git switch fix/v0.2.2-scroll-links-selection`
3. Continue from first unchecked item below.
4. Never report completion until release CI is green and APK is downloaded.

## Checklist
- [x] Fix Create Text PDF scroll lag/flash — compileDebugKotlin passed
- [x] Fix duplicate hyperlink annotations — unit tests and compile passed
- [x] Rewrite drag-selection hit testing and gesture updates — index tests and compile passed
- [x] Run builds/tests/lint — PR CI 35546555099 and main CI 35547042789 succeeded; local assembleRelease succeeded
- [x] Push branch and open PR — PR #11
- [x] Poll CI every 60 seconds — completed success
- [x] Merge to main — f1ac2ad75d2efc6b23b21351e068f7d0aebf4e10
- [x] Poll release workflow — run 35547042787 succeeded; APK verified as versionCode 6 / 0.2.2-beta
