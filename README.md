# eShareX 2.8.4

Android-native local sharing app with an embedded NanoHTTPD server and a shared web interface.

## 2.8.4 changes

- **Every phone keeps its own chat.** There is no "host" to switch to. Each app always shows its own chat and history; nearby phones sync in the background and nothing is ever deleted.
- **Group chat:** old group messages are not copied. When phones connect, the group chat simply continues from that moment, so nobody's group gets flooded with someone else's backlog. After about two minutes apart, a reconnect starts a fresh window the same way.
- **Direct messages:** full history is synced (union of both phones), so a conversation is never lost or split between phones. Uploaded files that belong to synced messages are copied too.
- **Browsers** open any phone's address and see that phone's own copy of the chat.
- **Works with no network:** you can join and chat on the phone itself with Wi-Fi/hotspot off (the page no longer treats "no internet" as "server offline"). The address shows as soon as Wi-Fi or a hotspot is on, and refreshes by itself.
- Automatic discovery (NSD + Wi-Fi scan), no Connect button, no "Host not reachable" screen. The six-digit group code is only a fallback: entering any phone's code adds that phone, and all phones then show the same code.
- **Faster messages:** the page now gets an instant "something changed" signal from its own phone (`GET /api/events/wait`, a long-poll), so new messages show up right away instead of on the next 5-second poll. When a phone gets a new message it also pokes nearby phones (`GET /api/sync/poke`), so they sync immediately instead of waiting for their 3-second timer. A Wi-Fi lock keeps Wi-Fi awake while the service runs, so delivery stays quick with the screen off.
- The page no longer stops updating when the phone has no internet (the leftover `navigator.onLine` checks are gone).
- Header: the menu button always stays on the right (Chat and Friends) and the upload card only uses free space, so nothing jumps while uploading.
- Sync endpoint: `GET /api/sync/export?since=<n>&groupSince=<n>` (open on the local network like the rest of the API).

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

