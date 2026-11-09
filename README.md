# eShareX — Native Android LAN sharing

This repository is the **native Java Android version** of eShareX. The previous Capacitor/Node.js project and its build workflow have been removed from this package so that there is one app implementation and one Android build workflow.

## What is included

- Native Android app (Java) with a WebView interface.
- Embedded NanoHTTPD server listening on port `8080` on all network interfaces.
- Local chat using HTTP polling (`GET /api/messages`, `POST /api/chat`).
- File upload and download endpoints (`POST /api/upload`, `/uploads/<file>`).
- Foreground hosting service and persistent notification while the room is hosted.
- Startup health check so the WebView waits until the local server is ready.
- Android file picker support for attaching files.
- One GitHub Actions workflow that builds and uploads a debug APK.

## Build the APK with GitHub Actions

1. Upload the **contents** of this folder to the root of your GitHub repository.
2. Commit and push to `main` (or `master`).
3. Open the repository's **Actions** tab and select **Build eShareX APK**.
4. Wait for the workflow to complete successfully.
5. Open the completed run and download the `eShareX-debug-apk` artifact.
6. Extract the APK from the artifact ZIP and install it on your Android phone.

The workflow uses the Android SDK already present on GitHub's Ubuntu runner and installs only the required Android 34 packages. It deliberately avoids the deprecated SDK package named `tools` that caused the earlier setup failure.

## Run eShareX

1. Install and open eShareX on the phone that will host the room.
2. Connect the host phone and joining devices to the same Wi-Fi network or hotspot.
3. Enter a display name and tap **Join Chat**.
4. Open the three-dot menu and choose **Show IP & QR Code**.
5. On another device, open the displayed `http://<host-ip>:8080` address in a browser.
6. Use **Share Chat Link** to share the host address. If the Android share sheet is unavailable, the address is copied instead.

Keep eShareX open while hosting. Android battery-management settings may still stop long-running background work on some devices.

## Build requirements

- JDK 17
- Android SDK Platform 34
- Android Build Tools 34.0.0
- Gradle wrapper is included (Gradle 8.2)

The app uses Android Gradle Plugin 8.2.2 and Java 17. No Node.js, npm, Capacitor, signing key, or GitHub secrets are required for a debug APK.

## API

| Method | Path | Purpose |
|---|---|---|
| GET | `/api/health` | Local server health check |
| GET | `/api/server-info` | Host IPv4 address and room URL |
| GET | `/api/messages` | Current in-memory chat history |
| POST | `/api/chat` | Send a JSON text message |
| POST | `/api/upload` | Upload a multipart file |
| GET | `/uploads/<stored-name>` | Retrieve an uploaded file |

## Important limitations and safety

- This is a **local-network** sharing tool, not a cloud service. It does not work across the public internet unless you separately configure a secure network route.
- There is currently no login, room password, end-to-end encryption, or transport encryption. Anyone who can reach port `8080` on the same network may be able to read messages and upload files. Use it only on a trusted network and do not share sensitive files.
- Chat history is stored in memory and is cleared when the host app process is restarted. Uploaded files remain in the app's private storage until the app's data is cleared or the app is uninstalled.
- The QR code library is loaded from a CDN, so QR rendering requires internet access. The room address remains visible and can be copied without the QR library.
- The UI's icons are built in and do not require an icon-font download.
- Large uploads depend on available phone storage and the browser/device limits.
- The app currently targets Android API 28 for broad compatibility. This is suitable for local testing, but target SDK and privacy/security hardening should be reviewed before any Play Store release.

## Repository layout

```text
.github/workflows/android.yml
app/build.gradle
app/src/main/AndroidManifest.xml
app/src/main/assets/index.html
app/src/main/java/com/esharex/app/MainActivity.java
app/src/main/java/com/esharex/app/ShareHttpServer.java
app/src/main/java/com/esharex/app/ShareServerService.java
build.gradle
gradle/wrapper/
gradle.properties
gradlew
gradlew.bat
settings.gradle
```
