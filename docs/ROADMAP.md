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
- [ ] Microphone capture with explicit runtime permission
- [ ] Android AudioPlaybackCapture for eligible apps
- [ ] Audio mixer, mute, gain, and synchronization tests
- [ ] Document apps/content that disallow playback capture

### M3 — Encoding and streaming
- [x] Surface-input H.264 hardware encoder selection with device capability checks
- [x] Initial adaptive 60/30 FPS selection and bounded 1920-pixel capture output
- [x] Encoded output drain and encoder FPS/bitrate diagnostics (encoded data is currently discarded)
- [ ] Runtime resolution/FPS/bitrate controls
- [ ] RTMP/RTMPS output using a reviewed, licensed implementation
- [ ] Reconnection, network monitoring, and dropped-frame metrics

### M4 — Streamer experience
- [ ] Floating controls with user-granted overlay permission
- [ ] Overlay editor and animated assets
- [ ] Stream health dashboard
- [ ] Optional alerts and platform integrations

### M5 — Accessibility and compatibility
- [ ] Define a legitimate, user-visible accessibility use case
- [ ] Implement only necessary AccessibilityService capabilities
- [ ] Do not use accessibility to bypass game, OS, or platform protections
- [ ] Test coexistence with screen capture and audio on supported devices

### M6 — Release readiness
- [ ] Privacy/data-flow review and privacy policy
- [ ] Open-source notices and licence compliance
- [ ] Device/Android-version compatibility matrix
- [ ] Battery, thermal, crash, and long-session testing
- [ ] Closed beta before public release

## Engineering guardrails

- MediaProjection consent is requested for each capture session as required by Android.
- Never capture or transmit without clear user action and persistent foreground notification.
- Audio capture is subject to Android policy, app opt-outs, and user permissions.
- Accessibility is optional and must be narrowly scoped and transparently disclosed.
- Do not promise 1440p60 until encoder capability and sustained thermal tests confirm it.
- Keep secrets, RTMP stream keys, and signing keys out of source control.
