# Contributing

## Issues: welcome

Bugs, rough edges, battery-cost observations, ideas for the notification
poller - if you can describe it concretely, open an issue. Reproduction
steps, device/Android version, and logcat excerpts make an issue
immediately actionable, but a clear description in plain Danish or
English is fine too.

## Pull requests: no

This project is run as a **benevolent dictatorship**: the maintainer
decides what the app is, what it ships, and how it is built - and takes
serious responsibility for the consequences (signing keys, the update
path, what millions of sideloads trust). That only works if one person
owns the merge button, so **pull requests are not accepted and will be
closed without review**.

This is not a "not invented here" rule. If an issue contains a working
fix - a patch attached to the issue, a diff, or even pseudocode - it is
read with the same interest as any PR, and if it is adopted you will be
credited in the issue thread. The rule is about process, not ideas: the
code that ships passes through the maintainer's hands either way, so
send the patch inside an issue instead of as a PR.

## Security issues: privately

Do not open a public issue for vulnerabilities. Report them privately
per [SECURITY.md](SECURITY.md).

## Releases: the maintainer cuts them

Releases are git tags (`vX.Y.Z`), and a pushed tag is write-once: any fix
after it is a new patch release, never a re-tag. A release cut therefore
always, in this order on `main`:

1. An entry in [CHANGELOG.md](CHANGELOG.md) describing what the release
   contains (Keep a Changelog format; derived from the commits since the
   previous tag - outcomes, not process).
2. The version bump in `android/app/build.gradle` (`versionName` exactly the
   tag without the `v`, `versionCode` +1).
3. The `chore(release): X.Y.Z` commit, then the tag.

The changelog entry ships in the same push as the bump: a release without one
is a mistake to be caught before tagging, not a todo for afterwards.

## Direction is not up for vote

Feature requests are welcome as discussion, but "will you merge X" has
no more authority than "will you *build* X" in any other single-owner
project - and this one explicitly says so up front so nobody wastes an
afternoon on a PR. If a decision ever becomes genuinely contested, the
dictator resigns; until then, the dictatorship is benevolent: issues are
answered, crash reports get fixed, and user-visible pain outranks
roadmap pride.
