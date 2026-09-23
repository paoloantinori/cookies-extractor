# WebView Cookie Extractor

A small Android app for **extracting your own session cookies** from a website after you
log in manually inside an in-app WebView; handy for passing them to `curl`, `wget`,
`yt-dlp`, or any script that needs an authenticated session.

It exists because headless automation (Selenium/Playwright, etc.) is increasingly blocked
by 2FA QR codes, interactive challenges, and anti-bot flows. Here you log in **by hand**,
exactly like in a normal browser, then tap one button to pull the session cookies.

## What it does
- Address bar + in-app WebView (links stay inside the app).
- Log in manually with **password / 2FA / OTP** (and most SSO-redirect flows).
- **FAB** → extracts all cookies for the loaded domain via `CookieManager` and sends them
  through the Android **share sheet** (messaging apps, clipboard, etc.).
- **Share template**: long-press the share FAB to define the message template used for
  both shares (cookies and captured OAuth tokens). Placeholders: `{payload}` (the
  cookie/token text), `{url}`, `{title}`, `{host}`, `{date}` (ISO-8601). Unknown
  placeholders stay as written; an empty template restores the default message.
- **Bookmarks** (overflow menu): add by URL or current entry URL, tap to reload, delete.
- **OAuth redirect capture**: non-http OAuth redirects (e.g. `urn:ietf:wg:oauth:2.0:oob?code=…`)
  are caught before the WebView shows an error page, and the **full redirect URL is shared as
  one line** (a URL has no spaces, so no paste or relay hop can corrupt the code). A Snackbar
  announces the capture, so the login's silent terminal state is no longer mistaken for a stall.
- **Session reset** (overflow menu): wipes cookies + WebView storage behind a confirm and
  reloads, for when a login flow gets wedged and needs a from-zero restart.
- **Session monitor** (overflow menu): polls a state document on your gateway and raises a
  native notification when a service's login expires (see the dedicated section).
- **Debug channel** (overflow menu, Developer options): opt-in HTTP endpoint to pilot the app from a laptop
  (see below).

## What it does NOT do
- **Passkeys / WebAuthn for third-party sites are not supported.** Android's security model
  only lets the system browser (or apps asset-linked to a site, or Google-approved
  "privileged" browser apps) assert passkey credentials for an origin. A third-party
  WebView app like this one cannot; use a password/OTP login instead. See `PRD.md` §4.3.
- No headless automation, no cross-app cookie theft, no root. It only reads cookies from
  its own WebView through the public `CookieManager` API.

## Build & run
Requirements: JDK 21, Android SDK (`compileSdk`/`targetSdk` 36).

```bash
./gradlew assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk
```

Toolchain: AGP 8.10.0, Kotlin 2.2.0, Gradle wrapper 8.11.1.

## Release signing

Release builds require signing credentials in
`~/.cookies-extractor/keystore.properties` (outside the repository; never commit it):

```properties
store.file=/absolute/path/to/release.keystore
store.password=...
key.alias=cookies-extractor-release
key.password=...
```

Generate a fresh key (one per app; do not reuse another app's certificate):

```bash
keytool -genkeypair -v -keystore release.keystore -alias cookies-extractor-release \
  -keyalg RSA -keysize 2048 -validity 10000
```

With the file in place, `./gradlew assembleRelease` produces a signed APK; without it,
debug builds and tests run normally while release builds fail with a pointer to this
section. Losing the key means every existing sideload install needs uninstall+reinstall:
back it up.

## Keeping personal data out

Development on this project runs with a pre-commit guard (not shipped in this repository)
that refuses commits whose added lines look like personal data or secrets: private IP
ranges, local home paths, the device hostname, and API tokens. Intentional commits bypass
it with `git commit --no-verify`.

## Scope
A personal power tool: extract your own session cookies from sites you log into.
Licensed under the Apache License 2.0 (see `LICENSE`).

## Project layout
- `app/src/main/java/com/cookiesextractor/app/`: Kotlin source (`MainActivity`,
  `RedirectCapture`, `BookmarksRepository`, `Bookmark`, `DebugHttp`, `DebugChannel`,
  `ShareTemplate`, `ShareTemplateRepository`, `Api`, `MonitorAlerts`, `MonitorRepository`,
  `MonitorPoller`).
- `app/src/main/res/`: layouts, strings, themes, launcher icon.
- `tools/gen_icon.py`: regenerates the ten legacy launcher PNGs (committed bytes are the
  Pillow output).
- `PRD.md`: product requirements (including the passkey limitation).

## Debug channel
Off by default. Enable it from the overflow menu (Developer options): the dialog shows the
URL (`http://<phone-ip>:8777`) and a per-enable random token. Every request must carry it
(`Authorization: Bearer <token>` or `?token=`); the server binds the phone's LAN address,
and `/navigate` accepts http(s) URLs only. Endpoints (GET):

| endpoint | returns / does |
|---|---|
| `/status` | JSON: current URL, page title, whether a capture is held |
| `/navigate?url=` | loads the URL in the WebView |
| `/text` | the page's rendered text (`document.body.innerText`) |
| `/screenshot` | JPEG of the topmost window (dialog when one is open, else the activity) |
| `/capture` | the captured OAuth parameters (404 if none held) |
| `/clear` | wipes cookies + storage and reloads (same as the overflow menu's Clear session) |
| `/console?n=50` | the last N page-console lines (recorded only while enabled) |
| `/tap?x=&y=` | dispatches a touch to the topmost window (dialog-aware) |

`/capture` and `/screenshot` expose session data by design: keep the channel disabled
except while testing, and only on networks you trust. Note (2026-09-08): Android plans a
Local Network Permission (staged in Android 16, enforcement expected around 2026 Q2) that
will require granting the app local-network access for this server to accept connections.

## Agent SPI (`/api/v1`)

The same channel also serves a versioned, JSON API for agents that need to drive the app
programmatically **without simulating UI touches**. One server, one token: every request
carries the channel token exactly like the legacy endpoints. `GET /api/v1/info` returns
the API level and the capability list; agents should feature-detect on it rather than
hardcode. Errors are always `{"error":{"code":"...","message":"..."}}`. Mutations are
GET-only, matching the channel's parser.

| endpoint | does / returns |
|---|---|
| `/api/v1/info` | name, version, `api_level`, `capabilities` (discovery) |
| `/api/v1/status` | current url/title, entry url, capture held, api level |
| `/api/v1/navigate?url=&wait=load` | loads the URL; `wait=load` answers after `onPageFinished` (10s cap, 504 on timeout). The channel serves one connection at a time: a waiting navigate holds it until it answers |
| `/api/v1/text` | `{"text": ...}` rendered page text |
| `/api/v1/cookies` | `{"url": ..., "cookies": ...}` for the current page; `?format=structured` returns the version-2 multi-domain JSON, identical to the untemplated share payload (404 `no_cookies` if the jar is empty) |
| `/api/v1/capture` | `{"url": ...}` the captured redirect (404 `no_capture` if none) |
| `/api/v1/clear` | wipes cookies + storage and reloads |
| `/api/v1/bookmarks` | list; `.../add?url=&title=` and `.../delete?url=` mutate |
| `/api/v1/template` / `.../set?t=` | read / replace the share template (empty `t` clears) |
| `/api/v1/console?n=50` | last N page-console lines |
| `/api/v1/fill?selector=&value=` | sets an input/textarea via JS (native setter + input/change events); selector is CSS, a bare name falls back to `[name=...]` |
| `/api/v1/submit?selector=` | clicks the element, or submits the first form (`requestSubmit` then `submit`) |

`/tap` and `/screenshot` are deliberately NOT part of the SPI contract: they exist as the
UI-simulation fallback for pages the API cannot drive. Threat model: the SPI can navigate,
read pages, and reveal cookies, exactly like a person holding the phone; it is off by
default and token-gated, so enable it only on networks you trust.

## Session monitor (native alerts when a login expires)

The overflow menu's Session monitor entry configures an **inbound** alert source: a state document on your own
gateway. When a service's session dies, your monitor (Uptime Kuma) tells the gateway, and
the app tells **you**, with a native notification: "Login needs redoing. Session for
`<service>` needs re-login. Tap to open it."

- **Wiring:** Kuma's Webhook notification provider POSTs its heartbeat payload to a small
  script on your gateway; that script maintains the state document the app polls. Any
  endpoint serving the documented JSON works, whatever produces it.
- **Document contract:** `{"alerts":[{"service":"...","url":"...","state":"expired|ok","ts":0}]}`
- **Configuration (overflow menu, Session monitor):** state-document URL, bearer token, poll period in minutes
  (15-240), enable switch, and a manual "Check now". Everything is persisted.
- **Behavior:** the app polls on the configured period (JobScheduler, survives reboots),
  notifies only on `ok -> expired` transitions (no repeat spam), and clears the
  notification when the service reports `ok`. Tapping the notification opens the app on
  the alerted URL.
- **Threat model:** like the debug channel, this is an inbound control surface: the app
  calls out to a URL you configured with a token you configured, and the payload controls
  which URL the app navigates to on tap. Point it only at gateways you control; keep the
  token secret. Latency is the poll period, not real time.

## Security note
Extracted cookies **are** session credentials; anyone holding them can impersonate your
session. Share them only over trusted channels.
