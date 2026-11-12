# eShareX 2.5.9

Local-network chat and file sharing for Android and web.

## 2.5.9 changes
- Fixed global keyboard viewport handling in General Chat and private conversations.
- Kept the current scroll position when focusing the composer; only conversations already at the bottom follow the latest message.
- Moved file-card Download text below the filename while keeping the whole card clickable.
- Sized image message bubbles to the rendered image and wrapped captions within that width.
- Removed excessive empty space from media previews by sizing the dialog and preview stage to the media.
- Corrected the server health endpoint version to 2.5.9 and advanced the PWA cache version.

## 2.5.6 changes
- Wider aligned desktop layout.
- Larger desktop media previews.
- Download controls in image/video previews and document cards.
- Clipboard API fallback and Android native clipboard bridge support.

## 2.5.5 changes
- Fixed notification event cursor handling so messages arriving during polling are not skipped.
- Fixed Android notification taps for General Chat.
- Read receipts now acknowledge the newest message actually loaded instead of the current wall-clock time.
- Corrected friend unread badge positioning and clarified browser notification HTTPS requirements.

## 2.5.4 changes

- Persistent unread counts for General Chat and each private conversation.
- Server-side read timestamps, so unread indicators survive refreshes and reconnects.
- Notification event polling that skips offline backlogs while keeping those messages unread.
- Android foreground notification monitor for the currently connected eShareX host, including message tap-through to the relevant DM.
- Browser notifications while the web app is running and permission has been granted.
- Notification suppression while the recipient is actively viewing the same conversation.
- Floating Friends and Chat menus, reduced Friends menu actions, and a right-facing reply control.
- Friends search/online filters reset when the Friends page is re-entered.

## Build

Use JDK 17 and Android SDK 34. Run `bash gradlew assembleDebug` (or `./gradlew assembleDebug` where the wrapper executable bit is preserved).

## Notification notes

Android message alerts use a foreground service to poll the connected LAN host while the app's notification monitor is running. Android notification permission must be granted, and battery-management settings may still affect long-running background work on some devices. Browser notifications require browser permission and a running page; true browser push after the tab/browser is closed requires HTTPS and a push subscription/backend, which this LAN-only release does not configure.

## Security note

This release is intended for trusted local networks only. The current LAN API identifies participants by device ID but does not yet provide cryptographic peer authentication or end-to-end encryption. Do not expose port 8080 to the public internet or use it for sensitive conversations on an untrusted network.


## 2.5.8 interface fixes
- Replying to an older message keeps that message anchored while the mobile keyboard opens; keyboard resizing no longer forces the message list to the latest item.
- Added a floating down-arrow in each chat pane when the reader scrolls away from the latest messages. Tapping it smoothly returns to the bottom.
- Simplified file attachment cards: the filename wraps naturally, the Download label is text-only, and the whole card remains the download target.


## 2.5.9 global chat and media fixes
- Keyboard opening now preserves the current reading position; chats already at the bottom stay pinned to the newest message. Reply anchoring remains active in both General Chat and private conversations.
- File cards place the Download label beneath the wrapping filename, with the entire card remaining clickable.
- Image message bubbles are sized to the rendered image; long captions wrap within the image width instead of widening the bubble.
- Media preview dialogs and stages use the media's natural dimensions and aspect ratio rather than stretching to fill a tall empty panel.
- Consolidated the 2.5.8+ stylesheet into the document head and removed CSS that had been appended after the closing HTML document.
