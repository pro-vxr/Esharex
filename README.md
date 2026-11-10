# eShareX 2.2.0

eShareX is a native Android host app with a browser-based local-network interface. The host phone runs a NanoHTTPD server on port 8080; other devices on the same reachable LAN can open the host address in a browser.

## What's included in this update

- Home (`/`), General Chat (`/chat`) and Friends (`/friends`) routes.
- Native Android launcher icon and dark, responsive interface.
- Android foreground notification while the host service is running.
- Local user registration with a persistent device ID stored in browser local storage.
- Online presence using periodic heartbeats (45-second online window).
- General group messages and one-to-one direct-message conversations.
- General and direct-message history persisted in the app's private storage.
- Image thumbnails, inline video playback, and file download cards.
- General-chat and per-conversation text export.
- Server information in the three-dot menu.
- Web app manifest and service worker shell cache for supported secure origins.

## Build

The GitHub Actions workflow builds a signed release APK (signed with a private keystore supplied through GitHub Actions secrets):

1. Push to `main` or `master`, or manually run **Build eShareX APK**.
2. Open the successful workflow run.
3. Download the `eShareX-apk` artifact.

The APK output is `app/build/outputs/apk/release/app-release.apk`.

**Install notes:** the downloaded artifact is a `.zip` — extract it and install `app-release.apk`. If an older eShareX (signed with a different key) is installed, uninstall it once first. Always increase `versionCode` in `app/build.gradle` for each new release.

## Run and connect

1. Install eShareX on the Android phone that will host the room.
2. Open eShareX and leave it running; the foreground notification indicates the host service.
3. Connect other devices to the same Wi-Fi/hotspot.
4. On another device, open the host address shown under the three-dot menu.
5. Choose a display name and join.

The host address is not shown below the Join button in the browser version. In the Android app, the local address is shown there when available.

## API overview

- `GET /api/health`
- `GET /api/server-info`
- `POST /api/register` — `{ id, name }`
- `POST /api/presence` — `{ id }`
- `GET /api/users?id=<deviceId>`
- `GET /api/messages`
- `POST /api/chat` — `{ userId, username, text }`
- `POST /api/dm` — `{ from, to, text }`
- `GET /api/dm?me=<id>&peer=<id>`
- `POST /api/upload` — multipart `file`, `username`, `userId`, `caption`
- `GET /api/export/chat`
- `GET /api/export/dm?me=<id>&peer=<id>`

## Important security and deployment notes

This is a local-network collaboration app, not an internet-facing secure messenger.

- Device IDs are client-generated identifiers, not cryptographically authenticated identities. A malicious participant on the same network could impersonate an ID or inspect/alter traffic.
- Direct messages are separated by sender and recipient in the API, but they are **not end-to-end encrypted** and should not be treated as confidential against the host or a network attacker.
- The server currently allows LAN requests and CORS broadly to support same-network browser clients. Do not expose port 8080 to the public internet.
- Presence is best-effort and expires after 45 seconds without a heartbeat.
- Chat and uploaded files are stored in the host app's private storage. This update adds persistence, but does not include cloud sync or multi-host replication.
- Browser PWA installation and service workers generally require HTTPS or localhost. On plain `http://<LAN-IP>:8080`, browser installation support varies by browser; the browser's own install/add-to-home-screen option may be unavailable.
- GitHub Actions builds new APK artifacts, but does not automatically install updates on existing devices. Users must install a newer APK. A future signed release/update flow is recommended.

## Compatibility

- Android minimum SDK: 24 (Android 7.0).
- Compile SDK: 34.
- Java: 17.
- HTTP server port: 8080.


## Version 2.2.0 UI and messaging update

- Simplified the shared header and reduced primary navigation to Chat and Friends.
- The joined session stays on Chat when browser Back is used; the landing page is shown again after logout.
- Reworked the join card and moved the landing footer closer to the content.
- Chat and Friends now share the same navigation/header placement.
- Added a conversation context header, full-width responsive chat layout, multiline composer, file/camera/voice controls, message copy/reply/edit actions, and swipe-right reply.
- Added general-chat message editing and private-chat file uploads.
- Added an Android 13+ notification permission request after the user chooses Join, without a separate in-app permission button.

The app is intended for trusted local networks. Messages and uploaded files are not end-to-end encrypted.


## Secure release signing setup

**Do not commit a keystore or signing passwords to this repository.** A keystore previously included in a ZIP/repository must be considered exposed and should not be used for future releases.

Create a new private release keystore on a trusted computer or in Termux, and keep a secure offline backup. For example:

```bash
keytool -genkeypair -v -keystore esharex-release.jks -alias esharex -keyalg RSA -keysize 2048 -validity 10000
```

Convert it to Base64 (Termux/Linux):

```bash
base64 -w 0 esharex-release.jks
```

Add these four **Actions secrets** in GitHub → Repository → Settings → Secrets and variables → Actions:

- `SIGNING_KEY_BASE64` — the Base64 output (do not paste it into chat or commit it)
- `SIGNING_STORE_PASSWORD` — keystore password
- `SIGNING_KEY_ALIAS` — `esharex` (or the alias you selected)
- `SIGNING_KEY_PASSWORD` — key password

The workflow decodes the private key into the temporary GitHub runner and signs the release APK. The workflow intentionally fails if these secrets are missing. Do not publish the keystore or passwords.

Because the old `app/esharex.keystore` was included in a distributed source ZIP, treat its signing identity as compromised. If an APK signed with that key has already been installed, the new private key will have a different signature: uninstall the old app once before installing the first APK signed with the new key. Back up any files you need first. Future releases signed with the new private key can update normally as long as you preserve that key and increase `versionCode`.
