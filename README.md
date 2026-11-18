# eShareX 2.8.1

Android-native local sharing app with an embedded NanoHTTPD server and a shared web interface.

## 2.8.1 changes

- **Upload card:** uploads now show in a rectangle in the chat header (right of the chat title). It shows a preview, the file name with its extension, the status (Waiting / Uploading / Sent / Failed) and one overall progress bar for the whole batch, e.g. `2/6 · Uploading`.
- **Sequential uploads:** files upload one after another instead of all at once, which stops the phone and host from choking on several large files.
- **Media bubbles:** the extra inner box is gone. Videos, files and images use the bubble itself as the card, with proper responsive sizes for phones and desktop (no more tiny video cards or collapsed image bubbles).
- **Video tiles:** thumbnails are made from the first frame only (no full download), load as they scroll into view, and release the video decoder afterwards.

## 2.8.0 changes

- **Discovery:** APK-to-APK discovery no longer gives up. It combines NSD with an automatic Wi-Fi/hotspot scan that keeps retrying while the app is open, resolves NSD services one at a time with retries, accepts both service-type spellings, and retries when NSD start/registration fails.
- **One shared group code:** a permanent six-digit code (kept until app data is cleared) is shown identically on every phone. Any phone's code lets a new phone find the host; no second code is needed. Wrong codes are rate-limited.
- **Freeze fixes:** the host server (chat history parsing) now starts on a background thread instead of the UI thread; uploads support HTTP Range and are cached by the WebView (they were `no-store`, so every image/video was re-downloaded); presence heartbeats no longer rewrite `users.json` every ping; the 4-hour wake-lock expiry is gone.
- **Video:** the "Open preview" button is removed; tap the video tile to open the preview. Thumbnails load lazily as they scroll into view.

## Build

Use the repository's GitHub Actions workflow to build the signed Android APK. Local Android SDK/Gradle setup is required for a local build.

## LAN limitations

The six-digit group-code fallback scans the local IPv4 /24 subnet for a matching eShareX server on port 8080. Both devices must be reachable on the same LAN. Router client isolation, VPNs, VLANs, blocked ports or unusual subnet masks can prevent discovery. The host must remain online; there is no host failover or cross-network relay.

