# Development roadmap

## Product objective

Build a reliable Android-first livestreaming app for mobile gamers. The first reference device is the Samsung Galaxy S25 Ultra; public distribution comes only after stability, privacy, compatibility, and licensing checks.

## Milestones

### M0 — Project foundation
- [x] Native Kotlin + Jetpack Compose project
- [x] GitHub Actions debug build workflow
- [x] User-approved MediaProjection permission flow
- [x] Foreground capture service and persistent notification
- [x] Confirm CI build succeeds and debug APK artifact is uploaded
- [x] Install and test initial screen capture on the reference device
- [ ] Validate H.264 encoder stability and output metrics on the reference device

### M1 — Capture validation
- [x] Consume ImageReader frames safely and close every acquired frame
- [x] Log capture dimensions, average frame rate, and latest frame interval
- [x] Handle projection revocation and release capture resources
- [ ] Validate rotation and display-size changes on a physical device
- [ ] Validate app backgrounding, screen lock, and notification Stop action
- [ ] Confirm sustained capture without memory growth or thermal instability

### M2 — Audio
- [x] Microphone capture with explicit runtime permission
- [x] Android AudioPlaybackCapture source integration for eligible apps (RECORD_AUDIO permission and app capture policy still apply)
- [ ] Audio mixer, mute, gain, and synchronization tests (physical validation pending)
- [ ] Document apps/content that disallow playback capture

### M3 — Encoding and streaming
- [x] Surface-input H.264 hardware encoder selection with device capability checks
- [x] Initial adaptive 60/30 FPS selection and bounded 1920-pixel capture output
- [x] Encoded output drain and encoder FPS/bitrate diagnostics (encoded data is currently discarded)
- [x] Add initial 720p/1080p/1440p, 30/60 FPS and orientation selections
- [ ] Validate each profile against real device codec capabilities (fallback ladder added; physical matrix still pending)
- [x] Add RootEncoder RTMP video-only transport foundation
- [ ] Verify RTMP/RTMPS connection and YouTube ingest on physical device
- [x] Add opt-in Android Keystore-backed encrypted stream-key storage; connection recovery remains manual and requires fresh MediaProjection consent
- [x] Live-output stall detection with a user-visible recovery message; automatic reconnect remains disabled because a fresh MediaProjection consent is required for a new capture session
- [ ] True dropped-frame telemetry (outgoing FPS is not a dropped-frame counter)

### M4 — Streamer experience
- [x] Floating controls with user-granted overlay permission
- [ ] Overlay editor and animated assets
- [x] Basic live output FPS and bitrate telemetry (full stream-health dashboard remains pending)
- [ ] Optional alerts and platform integrations

### M5 — Accessibility and compatibility
- [ ] Define a legitimate, user-visible accessibility use case before reintroducing any AccessibilityService
- [ ] Implement only necessary AccessibilityService capabilities (no service currently registered)
- [ ] Do not use accessibility to bypass game, OS, or platform protections
- [ ] Test coexistence with screen capture and audio on supported devices

### M6 — Release readiness
- [x] Source-level privacy/data-flow review and privacy policy aligned with the current manifest and Keystore implementation
- [ ] Open-source notices and licence compliance
- [ ] Device/Android-version compatibility matrix
- [ ] Battery, thermal, crash, and long-session testing
- [ ] Closed beta before public release
- [x] JVM unit tests for RTMP/RTMPS destination validation
- [x] CI runs unit tests, Android lint, and a release-readiness source audit before packaging
- [ ] Play Console policy review, signed release bundle, and store Data safety declaration

## Engineering guardrails

- MediaProjection consent is requested for each capture session as required by Android.
- Never capture or transmit without clear user action and persistent foreground notification.
- Audio capture is subject to Android policy, app opt-outs, and user permissions.
- Accessibility is optional and must be narrowly scoped and transparently disclosed.
- Do not promise 1440p60 until encoder capability and sustained thermal tests confirm it.
- Keep secrets, RTMP stream keys, and signing keys out of source control.


## Phase 3 — Studio experience (0.5.17-dev)
- [x] Animate live-status changes and broadcast workspace layout transitions.
- [x] Apply the selected Claymorphism/Classic theme to the floating control panel surface and status controls.
- [x] Preserve the floating bubble, drag-to-dismiss, stream stop, bitrate presets, local speaker mute, stream-audio mute, and YouTube chat.
- [ ] Validate layout, touch targets, overlay behaviour, and theme switching on the Galaxy S25 Ultra.

## Phase 4 — Release-readiness engineering (0.5.17-dev)
- [x] Add JVM tests for RTMP/RTMPS URL validation and malformed/unsupported destination rejection.
- [x] Run Android unit tests and lint in CI before APK packaging.
- [x] Add a source-level manifest/privacy/version/changelog audit to CI.
- [x] Correct the privacy policy for opt-in Android Keystore-backed stream-key persistence and remove stale AccessibilityService claims (no AccessibilityService is declared in this build).
- [x] Add a closed-beta and pre-release checklist.
- [ ] Complete physical-device, privacy/legal, SDK licence, signing, and Play policy review before public release.
