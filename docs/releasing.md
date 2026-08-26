# Releasing the extension

The extension is versioned with **semantic versioning** driven by
[release-please](https://github.com/googleapis/release-please) and
[Conventional Commits](https://www.conventionalcommits.org/).

## How a release happens

1. Commit to `main` using Conventional Commit prefixes:
   - `fix: ...` → patch bump (0.1.0 → 0.1.1)
   - `feat: ...` → minor bump (0.1.0 → 0.2.0)
   - `feat!: ...` or a `BREAKING CHANGE:` footer → major bump (0.1.0 → 1.0.0)
   - `chore:` / `docs:` / `refactor:` / `test:` / `ci:` → no version bump
   Only commits touching `extension/**` count toward the extension version.
2. release-please opens/updates a **Release PR** that bumps `versionName` in
   `extension/app/build.gradle.kts` and updates `extension/CHANGELOG.md`. It keeps updating
   as you push more commits.
3. **Merge the Release PR** when you want to cut a release. That:
   - creates tag `extension-vX.Y.Z` and a GitHub Release, then
   - builds the APK (`versionName` = the released version, `versionCode` = CI run number)
     and attaches `roadbook-extension-vX.Y.Z.apk` to the release.
4. Install: paste the release APK URL into the Hammerhead Companion app.

## Notes

- Normal pushes do **not** publish an APK — only merging the Release PR does.
- `versionCode` is the CI run number: monotonic (Android requires it to increase) but not
  tied to the semver number. That's fine and intentional.
- The version of record is the annotated `versionName` line in `build.gradle.kts`. The
  annotation is a **trailing comment on the same line**:
  `versionName = "X.Y.Z" // x-release-please-version`. release-please replaces the
  semver string on that exact line — don't move the comment to its own line or reformat it.
- The **backend** is not versioned this way — it deploys continuously with sha-tagged images.
