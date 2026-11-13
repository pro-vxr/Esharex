# eShareX 2.6.3

## 2.6.3 identity and continuity update

- Uses a stable account ID derived from the normalized username, so the same username reconnects consistently even when a browser reaches the server through a different local IP/origin.
- Migrates legacy duplicate rows for the same normalized username to that stable ID and updates references in general messages, direct messages, and per-user read state.
- Does not use IP address or browser origin as an identity key.
- Keeps media-type icons, compact attachment cards, responsive image bounds, caption wrapping, and composer-aware latest-message controls from 2.6.2.
- Android version 2.6.3 / versionCode 31; health endpoint and PWA cache version updated.

## Identity limitation

This build intentionally uses username-only identity because no PIN or password was requested. Usernames are case-insensitive and Unicode-normalized. Two different people choosing the same username will share one account identity, including its direct-message identity. This is not authentication and should only be used on a trusted local network. A secure distinction between people with identical usernames requires an additional credential or account identifier.


## 2.7.0 Android local-network auto-join

Android devices advertise the eShareX service over DNS-SD and discover peers on the same Wi-Fi/LAN. A deterministic service-name election selects the lexicographically lowest discovered Android service as the room host. Other Android devices automatically join that host after checking `/api/health`. The elected host keeps its local server running; joining devices use that host's Friends list and General Group, so messages are stored in one shared room. Discovery now acquires a Wi-Fi multicast lock while active and logs NSD resolution/start failures for diagnosis.

The elected host must remain online for the shared room to remain available. Automatic host failover and cross-subnet/internet discovery are not implemented in this release. Web clients can continue joining the elected host using its local address.
