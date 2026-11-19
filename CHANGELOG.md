# MobileGamingStreamer — Changelog

All builds published from the `main` branch are development prereleases for device testing.

## 0.5.0-dev — Floating stream controls

### Added
- Movable always-on-top control panel after the user grants Android Display over other apps permission.
- YouTube live-chat pop-out panel using the live video ID.
- Stop-stream control accessible while another app or game is open.
- Live video bitrate controls (1.5, 2.5, 4, 6 and 8 Mbps) without reconnecting.
- Stable CI debug signing key cache so future APKs can update the installed development app.

### Fixed
- APK upgrades previously could fail because each ephemeral CI runner generated a different Android debug signing key. The first 0.5.0 install may require uninstalling the previous debug build once; subsequent CI builds reuse the cached key.
- Floating chat input can receive keyboard focus.

### Notes
- YouTube chat needs the live video ID and is subject to YouTube/WebView sign-in and chat availability.
- Bitrate controls adjust video bitrate only; they do not yet change audio (audio mixing is not implemented).
- Development builds remain unsigned by a production release key and are not Play Store releases.

