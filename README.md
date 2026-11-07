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
3. "Pair with a computer" shows a 6-digit code; open the address it displays on a computer, paste your Cookie header and the code there.

## Notes
- Music / YouTube toggle on Home and Search. YouTube mode shows mixes and recommended videos; search is not limited to official tracks.
- Tracks now have a real length and a working seek bar: the server finishes transcoding before serving,
  and the app asks it to prefetch the next queue item so skips stay instant.
- Tap a single result to play it with an auto-mix queue; playlists/albums/mixes queue their own tracks.
  The queue screen supports jump, move, remove, and "Save" (in-app or private YouTube playlist).
- Downloads live in the app's private storage (`filesDir/downloads`). Cleartext HTTP is allowed for
  LAN/VPN setups; use HTTPS if you expose the server publicly.
- If YouTube rejects the session, screens show "Sign in again".
