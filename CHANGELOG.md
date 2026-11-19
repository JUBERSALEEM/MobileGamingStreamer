# MobileGamingStreamer — Changelog

Development builds are published as GitHub prereleases. Device and platform testing is required before production use.

## 0.4.0-dev — RTMP video streaming foundation and studio redesign

### Added
- RootEncoder-based RTMP video-only streaming service.
- Stream server URL and masked stream-key entry.
- Initial 720p, 1080p and 1440p output profile selection.
- 30/60 FPS selection and Auto/Landscape/Portrait output orientation selection.
- Android notification permission request on first launch (Android 13+).
- Custom neon-lime/cyan launcher icon and branded capture/live notification icon.
- App-specific APK filename: `MobileGamingStreamer-0.4.0.apk`.

### Improved
- Rebuilt dashboard with broadcast setup, capture test and system status sections.
- Safe drawing insets and consistent spacing around Android system bars.
- Stream key is masked in the setup field.

### Important testing limitations
- This is an initial video-only RTMP integration. A successful build does not prove that YouTube or another RTMP endpoint accepts the stream.
- Resolution, frame rate and orientation choices must be validated on the target device. 1440p/60 is not guaranteed.
- No microphone or game audio is sent yet.
- Stream key is currently held in app memory only and is not persisted securely.
- No AccessibilityService is required or registered by this app.

## 0.3.0-dev — H.264 encoder foundation and layout refinement

- Added surface-input Android MediaCodec H.264 hardware encoder connected directly to MediaProjection.
- Device capability checks choose 60 FPS when supported, otherwise 30 FPS.
- Encoder output diagnostics report encoded frame rate and bitrate; encoded frames are discarded pending RTMP transport.
- Capture output is scaled proportionally to a maximum 1920-pixel dimension.
- Dashboard respects Android safe drawing insets.

## 0.2.0-dev — UI and automatic APK releases

- Automatic GitHub Release for every successful build from `main`.
- Debug APK attached to each prerelease and downloadable Actions artifact.
- Redesigned dark gaming-studio dashboard with neon-lime accents and compact status cards.
