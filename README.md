# eShareX 2.6.1

Local-network file sharing and chat for Android and modern web browsers.

## 2.6.1 corrections

- Removed the literal `\\n` text node that was appearing above the application header.
- Server Management closes the top-level menu synchronously before requesting server details and opening the modal.
- Raised the server modal above menus and other overlays.
- Removed the redundant Enable notifications menu item. The existing first-open notification permission prompt remains unchanged.
- Reordered menu actions: Server Management, Export conversation, Install App (when available), Exit App, Logout.
- Replaced conflicting image-message sizing with one intrinsic-dimension sizing helper shared by General Chat and private chats.
- Image messages now scale down to a maximum of 260 px wide / 210 px high on narrow screens and 420 px wide / 300 px high on larger screens, preserving aspect ratio. Smaller images remain smaller.
- Caption width follows the displayed image width and long captions wrap inside that width.
- Image dimensions are recalculated after viewport resizing and orientation changes.
- Kept compact text-message typography and padding across Android WebView, mobile web, and desktop web.
- Updated Android version, health endpoint, and PWA cache identifier to 2.6.1.

## Validation

Source checks performed: HTML parser pass, JavaScript syntax pass, CSS syntax pass, menu-order assertions, server-menu-close assertion, first-open notification prompt presence, and version consistency. The Android Gradle build could not be run in this environment because `services.gradle.org` was unreachable. The signed APK must be verified by the repository's GitHub Actions workflow. Physical-device and browser testing are still required; this archive does not claim those tests have passed.

## Build

Use the included GitHub Actions workflow to build the signed release APK. It uses the repository's existing Actions signing secrets.
