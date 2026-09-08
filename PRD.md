# Product Requirements Document (PRD): WebView Cookie Extractor

## 1. Project Overview
**Objective:** Build a single-screen Android application that allows a user to navigate to any web application, manually authenticate (bypassing headless-automation blockers like 2FA QR codes and interactive logins), and extract the resulting session cookies for sharing. Passkey-only third-party logins are not supported — see §4.3.
**Target Audience:** A personal power tool, published for anyone who needs the same capability.
**Platform:** Android (Target SDK 35+; target the latest available — see §4.1).
**Language:** Kotlin.

## 2. Core Features
* **Dynamic Web Navigation:** An address bar allowing the user to input and load target URLs.
* **Manual Authentication:** A WebView configured to handle modern web standards, JavaScript, and DOM storage, supporting password, 2FA/OTP, and SSO-redirect logins. (Passkeys are out of scope — see §4.3.)
* **Session Extraction:** One-tap retrieval of all session cookies for the currently loaded domain.
* **Native Sharing:** Integration with the Android share sheet to send extracted cookies to messaging apps or the clipboard.

## 3. User Interface (UI) Requirements
A single `MainActivity` with three components:
1. **Top navigation bar:** A text field (`EditText`) for the URL and a "Go" button to load it into the WebView.
2. **Main content area:** A `WebView` occupying all space below the navigation bar.
3. **Action element:** A Floating Action Button (FAB) anchored bottom-right with a "Share" icon; tapping it triggers cookie extraction + share intent.

## 4. Technical Implementation Details

### 4.1. Starting Template & SDK
* **Base:** Start with the Android Studio "Empty Views Activity" template.
* **Target SDK:** 36 (Android 16). Play requires API 36 for new apps since 2026-08-31, so the project targets 36 to keep every distribution channel open (sideload needs nothing).
* **Alternative bootstrap:** Clone a boilerplate wrapper such as `MonsterTechnoGits/android-webview-wrapper` and strip unneeded features, retaining only the core WebView setup.

### 4.2. Required APIs and Dependencies
* `android.webkit.WebView` — render the web application.
* `android.webkit.WebViewClient` — force links to open in-app rather than the system browser.
* `android.webkit.CookieManager` — the singleton used to extract session state.
* `android.content.Intent` — specifically `Intent.ACTION_SEND` to pass extracted data to other apps.
* No authentication-bridge dependencies — passkeys/WebAuthn are not supported (see §4.3). Plain `WebView` + `CookieManager` + `Intent` suffice.

### 4.3. WebView Configuration & Passkey/WebAuthn (not supported)
Initialize the WebView with JavaScript and DOM storage enabled:
* `settings.javaScriptEnabled = true`
* `settings.domStorageEnabled = true`
* Set a `WebViewClient` to intercept URL loading.

**Passkey/WebAuthn — NOT supported (out of scope).** Android's security model prevents a third-party WebView app from asserting passkey credentials for origins it does not own: `WEB_AUTHENTICATION_SUPPORT_FOR_APP` requires the *website* to host `/.well-known/assetlinks.json` declaring this app (impossible for arbitrary third-party sites), and `WEB_AUTHENTICATION_SUPPORT_FOR_BROWSER` (WebAuthn for any site) requires **Google privileged-app approval** — granted only to genuine browsers, not a credential-extraction tool. A passkey bridge (COK-1.3) was implemented and verified on-device, then **removed** by decision: credential providers (e.g. Bitwarden) refuse third-party passkeys for non-asset-linked origins. Password, 2FA/OTP, and SSO-redirect logins are unaffected and fully supported.

### 4.4. Extraction Logic
Bind to the FAB click listener. On tap, read the current URL from the WebView and pass it to the CookieManager.
* Method: `CookieManager.getInstance().getCookie(currentUrl)`
* Error handling: if the return value is null or empty, show a `Toast`: "No cookies found for this session."

### 4.5. Sharing Logic
Take the CookieManager output string and build a share intent:
* Action: `Intent.ACTION_SEND`
* Type: `"text/plain"`
* Extra text payload: `"Here are the extracted session cookies:\n\n[COOKIE_STRING]"`
* Wrap in `Intent.createChooser()` to invoke the native share sheet.

## 5. Security & Permissions
* **Internet permission:** `<uses-permission android:name="android.permission.INTERNET" />` in `AndroidManifest.xml`.
* **Cleartext traffic:** Optionally add `android:usesCleartextTraffic="true"` to the application tag when testing against local HTTP dev servers.
* **Sandbox compliance:** The app operates strictly within its own sandbox. No root permissions or cross-app data extraction.

## 6. Open Items / Verification Needed
* Confirm local Android SDK + JDK meet the Android Gradle Plugin requirements (see build-environment check).

## 7. License
Apache License 2.0; see `LICENSE` at the repository root.
