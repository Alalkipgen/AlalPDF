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
- [ ] Fix duplicate hyperlink annotations
- [ ] Rewrite drag-selection hit testing and gesture updates
- [ ] Run debug build, unit tests, lintRelease, release build
- [ ] Push branch and open PR
- [ ] Poll CI every 60 seconds; inspect logs immediately on failure
- [ ] Merge to main
- [ ] Poll release workflow; download and verify APK
