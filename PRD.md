# Product Requirements Document (PRD): WebView Cookie Extractor

## 1. Project Overview
**Objective:** Build a single-screen Android application that allows a user to navigate to any web application, manually authenticate (bypassing headless-automation blockers like Passkeys, WebAuthn, and 2FA QR codes), and extract the resulting session cookies for sharing.
**Target Audience:** Internal use / developer tool.
**Platform:** Android (Target SDK 35+; target the latest available — see §4.1).
**Language:** Kotlin.

## 2. Core Features
* **Dynamic Web Navigation:** An address bar allowing the user to input and load target URLs.
* **Unrestricted Manual Authentication:** A WebView configured to handle modern web standards, JavaScript, DOM storage, and native OS authentication hand-offs (including Passkeys/WebAuthn — see §4.3).
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
* **Target SDK:** As of 2026, Google Play requires `targetSdk` within one year of the latest release (API 35 minimum). This is an internal/dev tool likely sideloaded, so Play compliance is not strictly required — but target the **latest available SDK** (35, or 36 when installed) to get the best native Passkey/Credential Manager support. (Earlier draft's "SDK 34" is now stale.)
* **Alternative bootstrap:** Clone a boilerplate wrapper such as `MonsterTechnoGits/android-webview-wrapper` and strip unneeded features, retaining only the core WebView setup.

### 4.2. Required APIs and Dependencies
* `android.webkit.WebView` — render the web application.
* `android.webkit.WebViewClient` — force links to open in-app rather than the system browser.
* `android.webkit.CookieManager` — the singleton used to extract session state.
* `android.content.Intent` — specifically `Intent.ACTION_SEND` to pass extracted data to other apps.
* **Credential Manager (for WebView Passkey bridging)** — the correct AndroidX coordinate is:
  ```kotlin
  implementation("androidx.credentials:credentials:<latest-stable>")
  ```
  > **Correction:** the earlier `androidx.credentialmanager:credentialmanager:1.2.2` is **not a published artifact** — that group does not exist and would break the Gradle build.
* **WebKit (required for the WebView ↔ Credential Manager passkey bridge):**
  ```kotlin
  implementation("androidx.webkit:webkit:1.12.0") // 1.12.0+ per the official WebView auth guide
  ```
* Exact version strings are pinned at implementation time from the live [Jetpack releases page](https://developer.android.com/jetpack/androidx/releases/credentials) — version numbers go stale.

### 4.3. WebView Configuration & Passkey/WebAuthn Support
Initialize the WebView with JavaScript and DOM storage enabled:
* `settings.javaScriptEnabled = true`
* `settings.domStorageEnabled = true`
* Set a `WebViewClient` to intercept URL loading.

**Passkey/WebAuthn is supported, but NOT automatic.** The earlier assumption — "a standard WebView automatically delegates WebAuthn to native Credential Manager if JavaScript is enabled" — is **incorrect**. Per the official guide [Authenticate users with WebView](https://developer.android.com/identity/sign-in/credential-manager-webview), you must wire the WebView's WebAuthn JS calls to the native Credential Manager API using `androidx.webkit` (1.12.0+). In practice this means `MainActivity` hosts not just a `WebView` but also a JS-bridge / digital-asset-links handler mediating the passkey handshake. **Treat this as its own implementation task** — it compiles clean but fails silently at runtime if skipped.

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
* Pin exact `androidx.credentials` and `androidx.webkit` stable versions at build time.
* Confirm local Android SDK + JDK meet the Android Gradle Plugin requirements (see build-environment check).
