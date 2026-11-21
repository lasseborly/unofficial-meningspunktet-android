# Meningspunktet Android

**An *unofficial* Android app for [meningspunktet.dk](https://meningspunktet.dk/)** -
not made or endorsed by Meningspunktet. A plain Android WebView around the live
site (no app framework, no bridge; start URL hard-coded in `MainActivity`, so
cookies and CORS behave first-party) plus native notifications. App id:
`dk.meningspunktet.unofficial.app`.

## Download

**[unofficial-meningspunktet-latest.apk](https://github.com/lasseborly/unofficial-meningspunktet-android/releases/latest/download/unofficial-meningspunktet-latest.apk)**
- always the newest stable release (prereleases do not carry this alias). Copy it
to the phone, open it, allow "install from
unknown sources". Each release page also lists `SHA256SUMS` and the release-key
fingerprint so you can verify the download (see the release notes). Updates
install straight over older releases as long as the signer fingerprint matches.

## Why? Notifications: direct polling instead of push

No FCM, APNs, or Mozilla Push - no Google/Apple/Mozilla server ever sees your
Meningspunktet activity. A foreground service polls the site's own endpoints
(`/notifications`, `/conversations/load-page`) with the session cookie
(`NotificationPollerService.java`): every 60 s while the app is open, every
5 minutes in the background. The cost is battery: periodic wake-ups burn
more than OS push. The gain: no third-party servers in the path, and it works
where FCM apps deliver nothing - GrapheneOS, which ships no Google Play services.

Alternative: the site as a browser PWA - cheaper on battery, but Web Push relays
through the browser vendor's push service (FCM on Chrome, Mozilla's on Firefox),
the servers this app avoids.

The one change on the Meningspunktet side that would replace this polling - an
MQTT push path plus a stable catch-up API - is written
up for the site's developers in
[docs/upstream-notification-api.md](docs/upstream-notification-api.md).

## License

EUPL v1.2 or later - see [LICENSE](LICENSE). Copyright 2026 Lasse Borly Grønbæk.
Covers the source code only; the Meningspunktet name/logo/artwork in `assets/`
and the icons remain Meningspunktet's property, used only to identify the
unofficial app against the real site.

## Release history

Notable changes per release: [CHANGELOG.md](CHANGELOG.md). Every release cut
adds its CHANGELOG.md entry in the same push as the version bump - see the
release section of [CONTRIBUTING.md](CONTRIBUTING.md).

## Security

Report privately per [SECURITY.md](SECURITY.md).

## Credits

99% of this app's code was written by **Qwen 3.8 Flash Next**, hosted by
Alexandra Instituttet. Later releases were built with **GLM 5.3 Flash** and
**DeepSeek v4.1 Flash** from Lyceum.
