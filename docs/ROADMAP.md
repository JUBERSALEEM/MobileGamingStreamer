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
- [x] Separate microphone and playback AudioRecord sessions; attach AEC/NS only to microphone capture.
- [x] Add 80 Hz microphone high-pass, PCM mix headroom protection and JVM DSP/mixer tests.
- [ ] Verify teammate voice routing, echo/noise suppression, headset/Bluetooth paths and long-session synchronization on Galaxy S25 Ultra.
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
- [x] Apply the claymorphism theme to the floating control panel surface and status controls.
- [x] Preserve the floating bubble, drag-to-dismiss, stream stop, bitrate presets, local speaker mute, stream-audio mute, and YouTube chat.
- [ ] Validate layout, touch targets, overlay behaviour, and the clay-only surfaces on the Galaxy S25 Ultra.

## Phase K — Custom Scene Studio (PLANNED — NOT STARTED)

Recorded during J3, expanded in K0 (audit: docs/K0_ARCHITECTURE_AUDIT.md).
Phase K builds a direct-manipulation canvas editor on the Phase J
rendering engine. It is complete ONLY when every subphase below meets
its acceptance criteria; shipping more fixed controls NEVER satisfies
Phase K. No Phase K implementation has begun.

### K1 — Scene and collection management
- [ ] Collections CRUD + scene membership; default/active scene semantics
- [ ] Flat-store migration opens existing data unchanged (tested)

### K2 — Source and layer management
- [ ] Reusable sources: one asset placed in N scenes, single identity
- [ ] Scene-local overrides; no accidental cross-scene mutation (tested)

### K3 — Direct-manipulation canvas (incl. free-transform model)
- [ ] Free transform (position/size/rotation) in layer/spec/resolve/codec v7
- [ ] Tap-to-select (topmost), drag-to-move, canvas zoom + pan
- [ ] Editor navigation unaffected; preview↔GL rect parity ≤ 1 px

### K4 — Transform and property inspector
- [ ] Corner/edge handles, rotation, numeric inspector, aspect per kind
- [ ] Rotation renders identically in preview and GL; immediate updates

### K5 — Layer organization
- [ ] Order / duplicate / delete / lock / visibility, persisted
- [ ] Locked layers reject canvas edits; groups only if model-clean

### K6 — Independent portrait and landscape layouts
- [ ] Per-orientation placements survive switches; sources shared
- [ ] Both Phase I canvas profiles correct in preview and output

### K7 — Themes, templates and presets
- [ ] Templates instantiate real-model scenes (no hardcoded art)
- [ ] User themes persist; template duplication and editing

### K8 — Safe live editing and scene switching
- [ ] Explicit push-live policy; invalid edits keep last-good stream
- [ ] J5 isolation pins green; controls never in output

### K9 — Widgets and stream interactions
- [ ] Clock/timer (existing) in the source model; no per-tick allocs
- [ ] Chat stays FUTURE (no chat data source exists); budgets unchanged

### K10 — Persistence, import/export and regression
- [ ] Import/export round-trips; corrupt-data recovery
- [ ] Parity on all encoder profiles; full suite + perf pins green

## Phase 4 — Release-readiness engineering (0.5.17-dev)
- [x] Add JVM tests for RTMP/RTMPS URL validation and malformed/unsupported destination rejection.
- [x] Run Android unit tests and lint in CI before APK packaging.
- [x] Add a source-level manifest/privacy/version/changelog audit to CI.
- [x] Correct the privacy policy for opt-in Android Keystore-backed stream-key persistence and remove stale AccessibilityService claims (no AccessibilityService is declared in this build).
- [x] Add a closed-beta and pre-release checklist.
- [ ] Complete physical-device, privacy/legal, SDK licence, signing, and Play policy review before public release.
