# Security Policy

## Supported versions

Only the latest release is supported. Security fixes ship in new releases;
there is no backporting.

## Reporting a vulnerability

Report suspected vulnerabilities privately to
[lasse@trylleskov.email](mailto:lasse@trylleskov.email). Please do not open a
public issue.

I aim to acknowledge reports within 7 days and, where confirmed, to ship a fix
in the next release before any public disclosure. Coordinated disclosure is
appreciated; I won't pursue good-faith research.

## Scope

This app is an unofficial WebView wrapper around meningspunktet.dk. Vulnerabilities
in the website itself belong to Meningspunktet, not to this project - report those
to them. In scope here: the Android wrapper and its notification poller, e.g.
credential/cookie handling, the polling and CSRF code paths, update/install
mechanics, and the release signing setup.
