# Changelog

All notable changes to the unofficial Meningspunktet Android app are
documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and the project
adheres to [Semantic Versioning](https://semver.org/): a MAJOR bump means a
breaking change (for example a signing key or app-id change that forces an
uninstall), MINOR means new features, PATCH means fixes only.

Releases are tagged in git and cut automatically by the `Release` workflow;
each release page carries the APK, `SHA256SUMS`, a CycloneDX SBOM and SLSA
build provenance. Verify downloads against the release-key fingerprint
(`c0e9e78bf3cceccac0358e3173e05c211dad2e588b70d843fce3e2e537216e93`) - see
[SECURITY.md](SECURITY.md).

## [Unreleased]

Nothing yet.

## [1.4.3] - 2026-11-21

### Changed

- Release builds are now minified with R8: the APK shrinks from
  ~2.2 MiB to ~0.3 MiB. No app behavior changes; the site loads exactly
  as before (verified on an emulator).

## [1.4.2] - 2026-11-21

### Fixed

- Adaptive launcher icon: the foreground was rendered at the logo size
  instead of the full 108dp canvas, so launchers scaled it up and the
  circular mask cropped the outer arcs - the icon looked zoomed and its
  colors off next to the site logo. The logo's exact colors were never
  altered; the canvas is now the correct 108dp in every density.

## [1.4.1] - 2026-11-21

### Fixed

- The `v1.4.0` release was cut from a broken state: the git tag pointed at
  the pre-bump commit (tagged before the `versionName` change landed on
  main), so the release built the wrong tree and shipped an SLSA provenance
  bundle whose APK subject digest did not match the published APK. The
  release was rebuilt from the correct tree; 1.4.1 is that rebuild, cut
  through the normal tag-push flow. No code changes since 1.4.0.

## [1.4.0] - 2026-11-21

### Added

- Pull-to-refresh: swiping down anywhere on the page reloads it.

## [1.3.0] - 2026-11-21

### Added

- Dependency lockfiles (`gradle.lockfile`, `buildscript-gradle.lockfile`)
  pin every resolved version, direct and transitive, for both debug and
  release configurations; a drifted or shifted version now fails the build
  instead of silently resolving. Dependabot update PRs therefore show the
  exact version change they make.
- A weekly dependency-graph submission job, so Dependabot's CVE alerts are
  computed from the app's real resolved classpath (and secret scanning plus
  push protection are enabled on the repository).

### Changed

- Android Gradle plugin 9.5.1 -> 9.5.2, androidx.activity 1.11.0 -> 1.13.0,
  androidx.core 1.17.0 -> 1.19.1, compileSdk 36 -> 37.
- Buildscript transitive dependencies forced above the versions AGP pins
  (BouncyCastle 1.87, Guava 33.7.2, jose4j 0.9.6, jdom2 2.0.6.1, Kotlin
  Gradle plugin 2.4.21). These run on the build machine only - the shipped
  APK's runtime classpath scans clean against the OSV database. The pinned
  build.gradle overrides stay in place: even AGP 9.6.0-alpha05 still pins
  the vulnerable versions, so they are load-bearing until a newer AGP ships
  fixed transitives.
- CodeQL's autobuild mode is unusable in this project (its Java detection
  forces a Java 17 daemon that AGP 9.5 lint rejects), so the CodeQL
  workflow builds with the same explicit `assembleRelease` step the release
  workflow uses.

### Fixed

- Lint's `MissingPermission` error on the poller's `notify()` is now fixed
  with an `areNotificationsEnabled()` guard instead of suppression.

## [1.2.0] - 2026-11-21

### Security

- Hardened the notification poller's release logging: server error-body
  excerpts are now only logged on debuggable builds, like message content.
- Downloads: the session cookie no longer rides a redirect off-site. The
  redirect chain is resolved first (bounded at 5 hops) and the cookie is
  attached only if every hop stays on `*.meningspunktet.dk`; anything else is
  handed to the browser without credentials.
- Certificate pinning for all app traffic to `meningspunktet.dk` (WebView,
  poller, downloads) via a network security config: the Let's Encrypt YR1
  intermediate and ISRG roots are pinned (any-of, so leaf renewals do not
  break the app), with a system-CA fallback after 2028-09-01 so the app is
  never bricked by a chain rotation.
- WebView resources are released and the pending file-chooser callback is
  cleared in `onDestroy`.

### Changed

- CodeQL now extracts from a real Gradle build (manual build mode) instead of
  source-only indexing; deeper Java analysis.
- Documented the subdomain-trust assumption in the URL allowlist: every
  `*.meningspunktet.dk` host is treated as first-party; to be tightened if the
  operator ever hosts third-party content on subdomains.

## [1.1.0] - 2026-11-21

### Added

- Adaptive polling cadence: 60 s while the app is visible (notifications are
  suppressed there anyway), 5 minutes in the background - roughly a third of
  the wake-ups with unchanged background delivery.

### Changed

- CodeQL added to CI, Dependabot updates grouped.
- The release workflow now attests build provenance (SLSA/Sigstore) and scopes
  its token permissions.
- Proposed an MQTT push path and catch-up API to the Meningspunktet team in
  [docs/upstream-notification-api.md](docs/upstream-notification-api.md).

## [1.0.0] - 2026-11-21

### Added

- First stable release. Plain WebView wrapper around https://meningspunktet.dk
  (cookies and CORS behave first-party; no app framework, no JS bridge), with
  native notifications from a foreground service that polls the site's own
  endpoints directly - no FCM/APNs/Mozilla push, which also makes it work on
  de-Googled ROMs such as GrapheneOS.
- Browser-style back gesture, downloads via the system DownloadManager
  (session cookie only for site URLs), file chooser, splash screens from the
  site logo, edge-to-edge layout matching the site's bars.
- Distribution: signed sideload APKs from GitHub releases with checksums, SBOM
  and build provenance; releases carry the stable
  `unofficial-meningspunktet-latest.apk` alias.

### Removed

- `androidx.profileinstaller` from the APK: nothing referenced it; this also
  dropped its exported `ProfileInstallReceiver` and the startup provider from
  the merged manifest.

## [1.0.0-rc.1] - 2026-11-21

### Fixed

- The notification poller no longer notifies about the user's own messages:
  sending changed `lastMessage.id`, which minted a new alert id and echoed the
  user's own text back as an alert. Conversation alerts now skip
  own-authored last messages, keep a stable alert id across own sends, and
  only quote bodies with a known non-own author id.
- Release artifacts: only stable releases get the `-latest` APK alias
  (prereleases no longer overwrite it).
- The emulator boot script waits for guest DNS resolution instead of a ping
  reply.

### Security

- Hardened the WebView wrapper and poller end to end: the exported activity's
  notification-tap URL extra now only accepts site-relative paths and
  `https://meningspunktet.dk` URLs (a `javascript:` extra would otherwise
  have executed in the authenticated page origin); external open restricted
  to http(s)/mailto/tel; backups and cleartext disabled (WebView cookies had
  lived in Auto Backup); boot receiver unexported; downloads carry the
  session cookie only for site URLs and strip path components from remote
  filenames; WebView file/content access, mixed content, debugging and
  third-party cookies all off; the host policy is enforced on committed pages
  too (main-frame POSTs bypass `shouldOverrideUrlLoading`); poller response
  and avatar buffers are capped and content logs stay on debug builds.

## [1.0.0-rc.0] - 2026-11-20

Initial prerelease. App id `dk.meningspunktet.unofficial.app`, signed with the
self-sideload release key (tags are write-once; every later release must keep
the same signer fingerprint).

[Unreleased]: https://github.com/lasseborly/unofficial-meningspunktet-android/compare/v1.4.2...HEAD
[1.4.2]: https://github.com/lasseborly/unofficial-meningspunktet-android/compare/v1.4.1...v1.4.2
[1.4.1]: https://github.com/lasseborly/unofficial-meningspunktet-android/compare/v1.4.0...v1.4.1
[1.4.0]: https://github.com/lasseborly/unofficial-meningspunktet-android/compare/v1.3.0...v1.4.0
[1.3.0]: https://github.com/lasseborly/unofficial-meningspunktet-android/compare/v1.2.0...v1.3.0
[1.2.0]: https://github.com/lasseborly/unofficial-meningspunktet-android/compare/v1.1.0...v1.2.0
[1.1.0]: https://github.com/lasseborly/unofficial-meningspunktet-android/compare/v1.0.0...v1.1.0
[1.0.0]: https://github.com/lasseborly/unofficial-meningspunktet-android/compare/v1.0.0-rc.1...v1.0.0
[1.0.0-rc.1]: https://github.com/lasseborly/unofficial-meningspunktet-android/compare/v1.0.0-rc.0...v1.0.0-rc.1
[1.0.0-rc.0]: https://github.com/lasseborly/unofficial-meningspunktet-android/releases/tag/v1.0.0-rc.0
