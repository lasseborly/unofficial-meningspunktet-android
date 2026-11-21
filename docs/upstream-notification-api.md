# Three upstream changes that would make de-Googled notifications cheap

Suggestions for the Meningspunktet developers, from the maintainer of the
unofficial Android client. Nothing here is required: the app works today by
polling the site's own endpoints. These three additions would make it cheaper on
battery, less brittle, and usable by other clients (desktop, CLI, widgets)
without any third-party push service in the path.

## What the app does today

`NotificationPollerService` keeps a foreground service and, at an adaptive interval
(60 s while the app is open, 5 minutes in the background):

1. `GET /notifications` with `X-Inertia: true`, a session cookie, and an
   `X-Inertia-Version` hash scraped out of the public homepage. When assets are
   rebuilt the server answers `409` and the client re-scrapes the hash.
2. `POST /conversations/load-page?conversationsPage=1` with `X-XSRF-TOKEN`
   decoded from the `XSRF-TOKEN` cookie (Laravel CSRF), plus `Origin`/`Referer`.
3. For conversations whose unread state is not visible in that payload,
   `POST /conversations/{uuid}/load-messages?messagesPage=N`, sometimes the first
   and last page, to quote the actual unread message.

That couples a native client to Inertia internals, the asset-version hash, CSRF
cookie handling and the page/HTTP shape of an AJAX paginator. The three
suggestions below replace that coupling with a small, documented surface.

## 1. A stable, versioned JSON feed for notifications and conversations

Two read-only endpoints under `/api/v1`, authenticated by the existing session
cookie, returning the same data the site already loads:

```http
GET /api/v1/notifications?since=<cursor>&limit=50
Accept: application/json
```

```json
{
  "data": [
    {
      "id": "01J...",
      "type": "reply",
      "title": "Svar fra Mette",
      "body": "…",
      "url": "/forum/…",
      "created_at": "2026-11-21T08:00:00Z",
      "unread": true
    }
  ],
  "meta": { "next_cursor": "01J..." },
  "unread": { "notifications": 3, "conversations": 1 }
}
```

```http
GET /api/v1/conversations?since=<cursor>
```

```json
{
  "data": [
    {
      "uuid": "…",
      "type": "group",
      "title": "…",
      "group": { "title": "…" },
      "avatar_file_id": "…",
      "last_message": {
        "id": 1234,
        "body": "…",
        "author": { "id": 9, "name": "…" },
        "created_at": "2026-11-21T07:59:00Z"
      },
      "unread": { "messages": 2, "threads": 1, "last_read_at": "2026-11-21T07:40:00Z" }
    }
  ],
  "meta": { "next_cursor": "…" }
}
```

What matters more than the exact shape:

- **Versioned prefix** (`/api/v1`) and a documented response contract.
- **No Inertia headers.** A plain `Accept: application/json` request must not
  need `X-Inertia`, an asset hash, or a 409 round-trip.
- **Documented unread semantics.** Today a client has to guess between
  `lastMessage.isUnread`, `hasUnreadReplies` and `hasUnreadMessages`, and whether
  `lastMessage.body` is safe to quote. One explicit field per conversation
  (`unread.messages`, `unread.threads`, `unread.last_read_at`, plus the newest
  inbound unread message when it is known) removes that guesswork and the
  extra `load-messages` requests it causes.
- **Stable `created_at`/cursor values** so a client can ask for "everything since
  the last poll" instead of re-reading page 1 and diffing.

Effort: a thin read-only controller on top of the queries the Inertia pages
already run. No change to the website is needed.

## 2. Revocable personal access tokens and conditional requests

Today a native client has to keep the WebView's session cookie and reproduce the
CSRF dance. Instead:

```http
POST /api/v1/tokens          # session-authenticated, from a settings page
→ { "token": "mp_…", "scopes": ["notifications:read"], "id": "…" }
```

- Shown once, stored hashed, listed and revocable per device (Laravel Sanctum
  already provides this shape).
- Read-only scopes are enough for a notifier; no write access to the account.
- The web app can keep using the session cookie; the token is an addition.

Pair it with cheap polling:

```http
GET /api/v1/notifications
Authorization: Bearer mp_…
If-None-Match: "…"        → 304 Not Modified when nothing changed
```

and a documented rate limit (`429` + `Retry-After`, or `X-RateLimit-*` headers).

Why it helps: the client stops extracting `XSRF-TOKEN` from a cookie jar, a leaked
device can be revoked without changing the password, and non-browser clients
become possible at all. It also removes the need for the bot filter to special-case
non-browser user agents on the public pages.

## 3. A server-sent events stream for unread state

The app already runs a foreground service. One long-lived HTTPS response is much
cheaper than a wake-up every 60 seconds:

```http
GET /api/v1/stream
Authorization: Bearer mp_…
Accept: text/event-stream
```

```
event: unread
data: {"notifications":3,"conversations":1}

event: notification
data: {"id":"01J...","title":"Svar fra Mette","url":"/forum/..."}

: heartbeat
```

- Standard SSE (`text/event-stream`), resumable via `Last-Event-ID`, heartbeat
  comment every 30 s so proxies do not time the connection out.
- Emits events from the same places that currently create notifications and
  messages; no new storage is strictly required for a first version.
- The client falls back to suggestions 1–2 (or today's polling) when the stream
  is unavailable, so this is purely additive.
- A WebSocket endpoint is equally fine; SSE is simpler to run behind ordinary
  HTTP/2 proxies and matches a one-way notifier.

Implementation note: PHP-FPM ties up a worker per open connection. For a small
number of clients that is fine; with Octane/Swoole or a small dedicated process
it scales further.

Why it helps: notification latency drops from up to 60 s to sub-second, and the
per-minute wake-ups that cost the most battery disappear. Crucially, it stays on
Meningspunktet's own servers — no FCM/APNs/Mozilla push relay in the path, which
is the whole point of the client.

## Scale: what this costs at 100k / 1M / 5M installs

"Installed" is not "active", but for this client the difference is smaller than
usual: the app runs a foreground service, so every installed device polls while
it is running. The table below assumes a fixed 60 second interval (the current
foreground rate), two feeds (notifications and conversations):

| Installs | req/s, one feed | req/s, both feeds | SSE connections |
|---|---|---|---|
| 10k | 167 | 333 | 10k |
| 100k | 1,667 | 3,333 | 100k |
| 1M | 16,667 | 33,333 | 1M |
| 5M | 83,333 | ~167,000 | 5M |

Suggestion 1 and 2 lower the *cost per request* (no Inertia/asset-hash round-trip,
most `load-messages` fetches disappear, 304s instead of JSON bodies) but they do
not lower the request count. At 5M devices the count is the problem: a classic
PHP-FPM request costs roughly 10–20 ms of CPU, i.e. on the order of a thousand
cores at 83k req/s; even a worker-reusing runtime (Octane, RoadRunner) and
Redis-side unread counters with ETags leave an 80–170 core floor plus a matching
query load. Conditional requests help CPU, not the 83k/s arrival rate.

Suggestion 3 changes the order of magnitude, and moves the cost into operations:

- **No PHP-FPM.** One worker per open connection means millions of workers. Use an
  event-driven gateway (Go, Node, Swoole/Octane) with tuned `somaxconn`/`ulimit`;
  100k–1M idle connections per node is realistic, at roughly 2 GB per 100k.
- **Churn dominates.** Heartbeats at 30 s are trivial (~5 MB/s fleet-wide). The
  expensive part is reconnects: at a 15–30 minute average connection lifetime,
  5M devices mean 3,000–6,000 new TLS handshakes per second. Budget CPU for the
  handshakes, not the events.
- **Fan-out needs pub/sub** (Redis, NATS) so any app server can deliver to the
  sockets holding the right users, with unread counters kept there rather than
  recomputed per request.
- **Reconnect storms** need jittered exponential backoff, `Retry-After`, and a
  slower fallback poll (5–15 minutes) when the stream is unavailable — Android
  Doze will drop sockets even while the foreground service keeps the process.
- **Deploys** must drain millions of connections gracefully.
- **Cost.** This is the connection and egress bill that FCM currently absorbs;
  it is real money per month at this scale, and it is why messengers use a push
  service. A de-Googled client deliberately trades that off against the site's
  own infrastructure.

### Long-polling is not the middle ground

Holding `GET /api/v1/notifications?wait=25` open looks attractive, but every
completed long-poll is still one request: at a 25 second hold that is 200k
requests/s at 5M devices - worse than 60 second polling - with a worker or event
loop slot occupied for the whole hold. Long-polling buys latency, not throughput.

What does reduce load without sockets is an **adaptive interval**: poll often only
while the user is actively using the app, rarely otherwise. This client now does
exactly that (60 s foreground, 5 minutes background): 16.7k req/s at 5M devices and
333 req/s at 100k, versus 83k/1.7k at a fixed 60 s. It composes with suggestions 1–2.

Design target: suggestions 1–2 make the API correct and cheap into the low
hundreds of thousands of installs; suggestion 3 is what carries it beyond that,
at a cost that should be planned rather than assumed.

## Scope and compatibility

- All three are additive: the website's Inertia pages, cookies and CSRF behaviour
  stay exactly as they are.
- Suggestion 1 alone already removes the hardest coupling; 2 and 3 are
  independent improvements.
- Until any of this exists, the app keeps polling and keeps working. This
  document is a request for a stable contract, not a demand for a rewrite.

Questions or a draft controller shape: open an issue in this repository
(`https://github.com/lasseborly/unofficial-meningspunktet-android/issues`) and it
can be forwarded, or contact the app maintainer directly (see `SECURITY.md`).
