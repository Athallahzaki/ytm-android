# YT Music Self-Hosted: Android client

Kotlin + Jetpack Compose (Material 3 / Material You) + Media3.

## Build
1. Install Android Studio (Koala or newer, JDK 17). Open this folder (`ytm-android`).
2. Let Gradle sync (Studio downloads Gradle 8.9 from `gradle-wrapper.properties`).
3. Run on a device/emulator (Android 8.0+).

## First run
1. Enter the server address (`http://<ip>:8080`), a username, and the registration key if you set one.
2. "Sign in with Google" opens an in-app browser. Sign in, and the app links your session automatically.
   If Google blocks sign-in in the embedded browser, use "Paste cookie manually".
3. "Pair with a computer" is a placeholder; it needs a server-side endpoint that isn't built yet.

## Notes
- Seek bar works on cached/downloaded tracks. Tracks streamed live from the server have no known
  length yet, so the bar shows elapsed time only.
- Downloads live in the app's private storage (`filesDir/downloads`). Cleartext HTTP is allowed for
  LAN/VPN setups; use HTTPS if you expose the server publicly.
- If YouTube rejects the session, screens show "Sign in again".
