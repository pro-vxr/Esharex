# eShareX 2.8.4

Android-native local sharing app with an embedded NanoHTTPD server and a shared web interface.

## 2.8.4 changes

- Message field: the background bar behind the field and send button is gone, so they float; the field keeps its original rounded box.
- Video bubbles are sized directly from the video's shape (about 250px landscape / 170px portrait on desktop, smaller on phones).
- Documents: icon on the left (vertically centred), then a dark box with the filename and extension on top and Download at the bottom right.

## 2.8.3 changes

- Upload card: preview, filename, then the progress bar with count and status on the same line (e.g. `2/10 · 65%`).
- Document messages have their dark inner card back, with the icon vertically centred and the Download link under the filename.
- Smaller media on desktop (video and images), lighter font weights, sender name nudged left, and no box around the message text field.

## 2.8.2 changes

- Upload card layout is now: preview, filename + extension, progress bar, then count and status. It matches the header row height, so the page no longer shifts down while uploading.
- Sender names and quoted replies keep their padding inside media bubbles (they were clipped).
- File cards are compact: the Download action sits under the filename instead of far to the right.
- Removed the white focus rectangle that appeared around the chat when scrolling on desktop browsers.
- Video thumbnails are captured after the seek and skip black frames, then release the decoder.

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

