# eShareX 2.5.4

eShareX is a local-network chat and file-sharing Android app with a browser interface served by the Android host.

## Build

- JDK 17
- Android SDK platform 34 and build-tools 34.0.0
- Run `./gradlew assembleDebug` for a debug APK.
- GitHub Actions builds the signed release APK when the configured signing secrets are present.

## 2.5.4 changes

- Friends and chat action menus now use fixed-position overlays and no longer shift the message/friend list.
- Reply side-arrow now faces right.
- Reply quote previews are compact and no longer use a heavy grey inset background.
- Message polling preserves the first visible message as a scroll anchor, so incoming messages below the reader do not pull the viewport downward.
- Friends online count excludes the current device; presence refresh is requested again after saved-session registration.
- Keyboard-open layout forces a dark viewport surface to avoid a white gap in mobile browsers.
- App version and service-worker cache are updated to 2.5.4.

## Notes

- Android Play Protect is controlled by Android. The app cannot bypass or suppress its first-install scan. Keep the release signing key consistent between updates.
- Browser installation requires a browser-supported secure origin and install prompt. A local HTTP address may not qualify; use the browser's Add to Home screen option if the install prompt is unavailable.
- Existing message text already stored as replacement characters cannot be reconstructed; newly sent Unicode/emoji depends on the sender keyboard and browser encoding.
