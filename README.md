# eShareX — Native Android build

This version replaces the Capacitor + embedded Node.js runtime with a small native Java Android host.

## What changed

- Native Java Android shell with WebView for the existing eShareX interface.
- Lightweight embedded NanoHTTPD server; no Node.js runtime is packaged.
- HTTP polling for chat updates instead of Socket.IO, so ordinary browsers can join.
- Multipart file uploads stored in the app's private files directory.
- Foreground service with an ongoing notification while hosting.
- Android file picker support for selecting files from the WebView.
- GitHub Actions workflow builds a debug APK automatically.

## Build on GitHub

1. Upload the contents of this project to the root of your eShareX repository (not the containing folder).
2. Commit to `main` or `master`.
3. Open **Actions → Build eShareX APK**.
4. Wait for the workflow to finish, open the run, and download the `eShareX-debug-apk` artifact.
5. Extract the APK from the downloaded artifact ZIP and install it on Android.

No signing secrets are required for the debug APK. A debug APK is for testing; a later Play Store or public release should use a persistent private signing key.

## How to use

1. Install and open eShareX on the Android phone that will host the room.
2. Keep the phone connected to the same Wi-Fi network or hotspot as the joining devices.
3. Enter a username and join the chat.
4. Open the menu and choose **Show IP & QR Code**.
5. Other devices open the displayed `http://IP:8080` address in their browser.

The host must keep the eShareX foreground service running. Android battery management can still stop background work on some devices.

## Important limitations in this first native port

- Chat history is held in memory and is cleared when the host process is restarted.
- This is a trusted local-network app, not an internet service. Anyone on the same reachable network can join; there is no password or encryption.
- The chat uses lightweight polling (about every 1.5 seconds), not Socket.IO.
- Very large uploads may be limited by available phone storage and memory.
- The QR code and Font Awesome icons currently use CDN resources and therefore need internet access to load. The basic chat and upload server itself is local.

## Requirements

- Android Studio / Android SDK
- JDK 17
- Gradle wrapper included
- Minimum Android version: Android 7.0 (API 24)
