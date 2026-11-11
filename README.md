# eShareX 2.5.4

Local-network chat and file sharing for Android and web.

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
