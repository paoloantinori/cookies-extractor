# WebView Cookie Extractor

A small Android app for **extracting your own session cookies** from a website after you
log in manually inside an in-app WebView — handy for passing them to `curl`, `wget`,
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
- **Bookmarks**: save the current page, tap to reload it later, swipe/delete to remove.
- **OAuth redirect capture**: non-http OAuth redirects (e.g. `urn:ietf:wg:oauth:2.0:oob?code=…`)
  are caught before the WebView shows an error page, and the **full redirect URL is shared as
  one line** (a URL has no spaces, so no paste or relay hop can corrupt the code). A Snackbar
  announces the capture, so the login's silent terminal state is no longer mistaken for a stall.
- **Session reset** (trash button): wipes cookies + WebView storage behind a confirm and
  reloads, for when a login flow gets wedged and needs a from-zero restart.
- **Debug channel** (developer options): opt-in HTTP endpoint to pilot the app from a laptop
  (see below).

## What it does NOT do
- **Passkeys / WebAuthn for third-party sites are not supported.** Android's security model
  only lets the system browser (or apps asset-linked to a site, or Google-approved
  "privileged" browser apps) assert passkey credentials for an origin. A third-party
  WebView app like this one cannot — use a password/OTP login instead. See `PRD.md` §4.3.
- No headless automation, no cross-app cookie theft, no root. It only reads cookies from
  its own WebView through the public `CookieManager` API.

## Build & run
Requirements: JDK 21, Android SDK (`compileSdk`/`targetSdk` 35).

```bash
./gradlew assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk
```

Toolchain: AGP 8.10.0, Kotlin 2.2.0, Gradle wrapper 8.11.1.

## Keeping personal data out

A pre-commit guard (`git-hooks/pre-commit`) refuses commits whose added lines look like
personal data or secrets — private IP ranges, local home paths, the device hostname, and
GitHub/Telegram tokens. It's active in this clone via `core.hooksPath`; in a fresh clone
enable it with `git config core.hooksPath "$PWD/git-hooks"`. Bypass an intentional commit
with `git commit --no-verify`.

## Scope
Internal / developer tool; sideloaded (not published to Google Play).

## Project layout
- `app/src/main/java/com/cookiesextractor/app/`: Kotlin source (`MainActivity`,
  `RedirectCapture`, `BookmarksRepository`, `Bookmark`, `DebugHttp`, `DebugChannel`).
- `app/src/main/res/`: layouts, strings, themes, launcher icon.
- `tools/gen_icon.py`: regenerates the ten legacy launcher PNGs (committed bytes are the
  Pillow output).
- `PRD.md`: product requirements (including the passkey limitation).
- `backlog/`: task tracking (Backlog.md).

## Debug channel
Off by default. Enable it from the developer-options (wrench) button: the dialog shows the
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
| `/clear` | wipes cookies + storage and reloads (same as the trash button) |
| `/console?n=50` | the last N page-console lines (recorded only while enabled) |
| `/tap?x=&y=` | dispatches a touch to the topmost window (dialog-aware) |

`/capture` and `/screenshot` expose session data by design: keep the channel disabled
except while testing, and only on networks you trust.

## Security note
Extracted cookies **are** session credentials — anyone holding them can impersonate your
session. Share them only over trusted channels.
