# eShareX 2.7.1

Android-native local sharing app with an embedded NanoHTTPD server and a shared web interface.

## 2.7.1 changes

- Adds temporary four-digit Android-to-Android LAN pairing as a fallback to NSD discovery.
- Stores the paired host's stable Android device ID and periodically rediscovers that host to follow LAN IP changes.
- Keeps the host as the single source of truth for Friends, General Group, private messages and uploads.
- Updates Friends cards in place rather than rebuilding the entire list during each poll.
- Prevents overlapping message/user refreshes and slows background polling while skipping hidden tabs.
- Scrolls the newly uploaded image into view when the conversation was already at the bottom, including after the image finishes loading.

## Build

Use the repository's GitHub Actions workflow to build the signed Android APK. Local Android SDK/Gradle setup is required for a local build.

## LAN limitations

The four-digit pairing fallback scans the local IPv4 /24 subnet for a matching eShareX server on port 8080. Both devices must be reachable on the same LAN. Router client isolation, VPNs, VLANs, blocked ports or unusual subnet masks can prevent discovery. The host must remain online; there is no host failover or cross-network relay.

See [LAN-TESTING.md](LAN-TESTING.md) for the two-device test plan.
