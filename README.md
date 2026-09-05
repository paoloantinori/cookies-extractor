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
- **Bookmarks**: save the current page, tap to reload it later, swipe/delete to remove.

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
  `RedirectCapture`, `BookmarksRepository`, `Bookmark`).
- `app/src/main/res/`: layouts, strings, themes, launcher icon.
- `tools/gen_icon.py`: regenerates the ten legacy launcher PNGs (committed bytes are the
  Pillow output).
- `PRD.md`: product requirements (including the passkey limitation).
- `backlog/`: task tracking (Backlog.md).

## Security note
Extracted cookies **are** session credentials — anyone holding them can impersonate your
session. Share them only over trusted channels.
