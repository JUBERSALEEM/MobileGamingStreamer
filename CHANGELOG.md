## 0.5.22-dev — Glip-style concurrent microphone capture

### Added
- Add an explicit, user-enabled Android Accessibility Service for the concurrent microphone-capture path used by Glip/RootEncoder.
- The service does not retrieve window content, inspect screen text, perform gestures or automate other apps; it listens only for window-state events required by the accessibility-service declaration.
- Route microphone recording through a manager created by the connected accessibility-service path.
- Select Android microphone sources using the observed Glip pattern: DEFAULT with the accessibility bridge and built-in microphone, VOICE_RECOGNITION with an external headset, and MIC as the fallback when the bridge is disabled.
- Keep AEC/NS from suppressing acoustically captured phone-speaker voice on the built-in speaker route; the existing high-pass filter and independent internal-playback mixer remain active.
- Add an in-app setup control that opens Android Accessibility settings and reports whether the bridge is enabled.
- Add unit tests for all three microphone-source selection cases.

### Verification boundary
- CI verifies compilation, tests, lint and APK packaging. Actual concurrent BGMI voice-chat capture, speaker audio pickup, echo behaviour and YouTube audio output still require testing on the Galaxy S25 Ultra.
- Android playback capture remains subject to the game's playback usage and capture policy. Accessibility microphone sharing does not grant unrestricted access to protected playback streams.

## 0.5.21-dev — APK installation and signing continuity

### Fixed
- Pin one development signing identity in the repository, explicitly configure Android Gradle Plugin to sign debug APKs with it, and use that certificate for every pull-request, branch-push and manually dispatched APK.
- Verify the APK certificate fingerprint against the pinned keystore before packaging or publishing.
- Keep the requested 60 FPS across lower-resolution hardware fallbacks before dropping to 30 FPS, and cover fallback ordering/orientation with unit tests.
- Remove event-scoped Actions cache signing-key drift from the distributable build path.
- Because the historical 0.5.19 and 0.5.20 APKs were already signed with different certificates, users upgrading from either historical build may need one uninstall/reinstall to move onto the pinned identity.

### Verification
- CI checks the APK signature and exact certificate fingerprint. Device installation still needs confirmation on the Galaxy S25 Ultra.

## 0.5.20-dev — Video frame pacing and capture smoothness

### Fixed
- Pace OpenGL screen-capture frames to the selected H.264 encoder FPS instead of allowing the display refresh rate to feed the encoder unchecked.
- Match MediaProjection forced-render fallback cadence to the active 30/60 FPS profile rather than a fixed 15 FPS.
- Preserve the existing hardware H.264 profile fallback, RTMP transport, audio sources and stream lifecycle.

### Verification boundary
- CI verifies compilation, unit tests and lint; sustained 30/60 FPS, frame pacing and thermal performance still require live testing on the Galaxy S25 Ultra.

## 0.5.19-dev — Audio capture lifecycle hardening

### Fixed
- Release both AudioRecord instances safely when stream preparation fails before recorder threads start.
- Attach AEC/NS explicitly to the microphone AudioRecord session and release those effects during stop; playback capture never receives microphone effects.
- Keep the audio-source cleanup path safe for partial startup and repeated stop/release calls.

### Verification
- Re-run JVM DSP/mixer tests, Android lint and APK compilation in CI.
- Device validation of actual BGMI voice routing, echo cancellation, noise suppression, audio route changes and long sessions remains required.

## 0.5.18-dev — Microphone DSP and isolated game-audio mixing

### Added
- Capture microphone and eligible Android playback using independent AudioRecord sessions.
- Attach Android platform Acoustic Echo Cancellation and Noise Suppression to the microphone session only; game playback bypasses voice processing.
- Add an 80 Hz microphone high-pass filter for handling noise, low-frequency rumble and wind/plosive energy.
- Add PCM mixing with peak headroom protection and move stream audio input to 48 kHz.
- Add JVM tests for high-pass response, speech-band preservation, mixer headroom and microphone continuity when playback frames are absent.

### Audio capture limits
- Microphone and captured game playback are processed independently, then combined into one AAC stream track; viewers do not receive separate selectable tracks.
- Teammate voice is included only when the game routes it through Android playback usages allowed by AudioPlaybackCapture. Apps can opt out, and voice-communication usage is not generally capturable.
- Platform AEC/NS effectiveness depends on device, audio route and vendor implementation. The high-pass filter is not adaptive noise cancellation. AGC is not enabled by default because it can raise background noise between speech phrases.

### Verification boundary
- CI covers JVM DSP/mixer tests, Android lint and APK compilation.
- Actual BGMI teammate-voice routing, speaker echo reduction, headset/Bluetooth routing, microphone quality and long-session stability require Galaxy S25 Ultra testing.

## 0.5.17-dev — Phase 3 studio polish and Phase 4 release-readiness checks

### Phase 3 — Studio experience
- Animate broadcast status transitions and the broadcast workspace's content-size changes.
- Apply the selected Claymorphism or Classic theme to the floating control panel surface and state controls.
- Preserve the floating bubble, drag-to-dismiss, bitrate presets, local speaker mute, stream-audio mute, YouTube chat and explicit stop/cancel actions.

### Phase 4 — Release-readiness engineering
- Extract RTMP/RTMPS destination validation into a pure JVM helper and add positive/negative unit tests.
- Run JVM unit tests and Android lint in CI before packaging the debug APK.
- Add a CI source audit for manifest permissions/service declarations, privacy disclosures, and version/changelog consistency.
- Correct the privacy policy: opt-in stream-key persistence is AES-GCM encrypted with an Android Keystore key; no AccessibilityService is declared in this build.
- Add a practical pre-release/closed-beta checklist.

### Verification boundary
- CI can verify compilation, unit tests, lint and source-level declarations.
- Galaxy S25 Ultra tests are still required for Clay/Classic rendering, overlay touch/layout, RTMP delivery, microphone/game audio, echo/noise suppression, rotation, thermal behaviour and long-session stability.
- This is a development APK, not a Play Store release approval or signed production build.

## 0.5.16-dev — Phase 2 live stream health watchdog

### Added
- Monitor outgoing bitrate after the first successful live confirmation.
- Detect a live output stall lasting 20 seconds and stop the stalled session with a clear retry instruction.
- Require a fresh user-approved MediaProjection session for a retry; never silently reuse an Android projection token.
- Guard disconnect callbacks during intentional stop so a late callback cannot overwrite the user's final stop reason.

### Verified in code
- Ordered hardware H.264 preparation fallback across requested and lower supported profiles.
- Outgoing FPS and bitrate telemetry; FPS is not represented as a dropped-frame count.
- Explicit retry guidance for RTMP connection/authentication failures.

### Physical validation still required
- Rotation, selected-app resizing, sustained FPS, thermal behaviour, real network-loss recovery and long-session stability on Galaxy S25 Ultra.
- Android 14+ MediaProjection token restrictions prevent safe automatic recreation of the capture VirtualDisplay from a previously used token.

## 0.5.15-dev — Phase 3 secure RTMP destination and key handling

### Fixed
- Add optional encrypted stream-key persistence using an AES-256-GCM key held by Android Keystore.
- Keep only the encrypted payload in app-private preferences; plaintext is never written to preferences or logs.
- Add a visible opt-in control to remember the key securely on this device, with a clear opt-out that deletes the stored ciphertext.
- Restore a remembered key after app relaunch when enabled.

### Preserved
- RTMP/RTMPS URL and non-empty key validation before capture permission and service startup.
- Masked stream-key entry, YouTube RTMP destination default, H.264 profile fallback and connection/authentication status.
- Failed RTMP connections end the current capture session with a clear message; retry requires the user to start GO LIVE again and grant a fresh Android capture session.

### Security and validation
- Stream keys are sent only to the RTMP/RTMPS destination configured by the user.
- Prefer RTMPS where the provider supports it; plain RTMP does not encrypt transport traffic.
- CI build and encryption/decryption code-path tests required; device validation remains pending.

## 0.5.14-dev — Phase 2 encoder fallback and stream health

### Added
- Ordered hardware H.264 profile fallback when the requested resolution/FPS cannot be prepared.
- Fallback ladder preserves the requested profile first, then tries lower FPS and/or lower resolution.
- Live output FPS telemetry alongside the selected encoder profile and outgoing bitrate.
- Clear retry guidance after RTMP connection failure or disconnection.

### Important platform constraint
- Automatic reconnection is not attempted with a previously used MediaProjection token. Android 14+ limits a projection session to one VirtualDisplay creation; a fresh user-approved capture session is required for a safe new attempt.

### Still requires physical-device validation
- 720p/1080p/1440p at 30/60 FPS on the Galaxy S25 Ultra.
- Selected-app capture resizing, rotation, sustained FPS, thermal behaviour and actual network recovery.
- The FPS counter measures outgoing frames; it is not a dropped-frame counter.

## 0.5.13-dev — Phase 1 reliability and audio correctness

### Fixed
- Removed the mandatory Accessibility-service gate because the existing service did not perform microphone or compatibility work.
- GAME-only playback capture no longer starts the microphone foreground-service type; Android still requires RECORD_AUDIO permission for AudioPlaybackCapture.
- Stream-audio mute now covers InternalAudioSource as well as microphone and mixed audio sources.
- Restored broadcast state is validated against a live service heartbeat to avoid showing stale LIVE status after unexpected process termination.
- Floating controls use the same heartbeat validation when restoring broadcast state.

### Preserved
- MIC, GAME, NORMAL and BALANCED audio modes.
- Stream-key validation and per-session MediaProjection consent.
- Floating bubble, bitrate controls, local speaker control and explicit Stop Broadcast action.
- Existing Classic and Claymorphism themes.

### Verification
- CI debug build required before installation.
- Physical-device checks still required for microphone echo, AEC/NS effectiveness, game audio capture and long-session stability.

## 0.5.12-dev — Claymorphism studio redesign

### Added
- Reworked the Claymorphism palette into a warm lavender, cream, coral and mint visual system.
- Added a richer streaming-studio hero with clear on-air, connecting, capture and standby states.
- Added sculpted destination fields and segmented video/audio profile controls.
- Refined header hierarchy, spacing, card depth and tactile selection states.

### Preserved
- Classic theme remains available.
- Existing RTMP validation, stream-key prompt, screen-capture consent and audio-mode choices remain in place.
- Floating controls, notification controls and explicit End Broadcast behaviour are unchanged.

### Validation
- Android CI build is required before this development APK is considered installable.
- Clay rendering and touch interaction still require testing on the Galaxy S25 Ultra.
- Visual changes do not imply that audio echo cancellation or game-audio capture has been device-verified.

## 0.5.11-dev — Microphone input routing and stream audio controls

### Fixed
- Use Android's DEFAULT microphone source so the platform echo-cancellation and noise-suppression effects can attach where supported, instead of switching to VOICE_RECOGNITION when a headset is connected.
- Select a connected wired, USB or Bluetooth headset microphone as the preferred input device when Android exposes one.
- Keep microphone processing separate from input-device selection; headset detection no longer changes the Android audio source mode.

### Added
- Add a dedicated floating control to mute/unmute the stream's microphone-containing audio source.
- Keep the existing local speaker-volume control separate from the new stream-audio mute control.
- Preserve the floating bubble, stream lifecycle and explicit End Stream controls.

### Important behaviour
- The stream-audio mute control applies to microphone-only and mixed microphone/game audio sources. It is not a master mute for internal-game-audio-only mode.
- Echo cancellation and noise suppression are requests to Android's audio effects; actual effectiveness depends on the device, route and microphone/speaker acoustics.

### Validation
- CI compilation is required for this version.
- Galaxy S25 Ultra tests are still required for speaker echo, microphone quality, BGMI game audio, teammate voice chat and long-session stability.

## 0.5.10-dev — Required stream-key prompt and complete Claymorphism surface system

### Fixed
- GO LIVE validates the RTMP server URL and stream key before requesting microphone/screen-capture permissions or starting any foreground service.
- An empty stream key opens a blocking dialog with a masked key field. Saving a non-empty key continues the normal GO LIVE flow.
- Whitespace-only keys are rejected; the URL is trimmed and must contain a valid RTMP/RTMPS scheme and host.
- The stream key is not included in validation/status messages.

### UI
- Replaced flat Material cards across the main, settings and about screens with a shared raised-surface component.
- Clay mode uses opposing soft drop shadows, layered gradients, sculpted rims, recessed input fields and raised buttons with pressed-state elevation.
- Kept Classic dark mode styling separate from Clay mode.
- Centralised Clay surfaces and shadows in the shared theme instead of repeating unrelated gradients on individual screens.

### Validation
- CI compilation and Galaxy S25 Ultra device testing are required before treating the UI or validation flow as verified.

## 0.5.9-dev — Keep broadcast running when app task is dismissed

### Fixed
- Removed the RTMP service behaviour that stopped a live broadcast when the app Activity task was swiped from Recents.
- Removed the floating-controls service behaviour that sent a stop request when the app task was removed.
- Closing/minimizing the app or switching away from a selected app is no longer treated as an End Stream command. The foreground RTMP service remains responsible for the active broadcast.
- Explicit Stop actions in the app, floating panel and persistent broadcast notification continue to end the stream.

### Platform limits
- Android can still terminate MediaProjection if the user stops screen sharing from system controls, revokes capture permission, force-stops the package in Android Settings, or the OS kills the app process. No app can guarantee a projection survives an actual force-stop or system revocation.
- If only one app is being captured and it is temporarily not foregrounded, the broadcast service does not intentionally stop; Android may show a blank/paused capture surface depending on the OS and target app state.

### Device validation required
- Verify that swiping Mobile Gaming Streamer from Recents does not stop RTMP, reopening the app restores the current stream state, and only explicit Stop actions end the stream.
- Verify single-app capture behaviour on the Galaxy S25 Ultra.

## 0.5.8-dev — Floating controls restoration hotfix

### Fixed
- Restore the floating bubble immediately after the user approves screen/app capture and GO LIVE starts, rather than waiting for the first outgoing bitrate.
- Explicit GO LIVE and the manual SHOW FLOATING CONTROLS action clear the previous drag-to-dismiss preference, so a previously hidden bubble cannot silently prevent testing or monitoring a new stream.
- Preserve drag-to-close, panel minimize and the existing overlay service implementation.

### Device validation still required
- Verify the bubble appears immediately after screen-capture approval, remains available during Preparing/Connecting/Error, and can be dragged to the close target.
- Verify stream state, RTMP connection and all audio sources on the Galaxy S25 Ultra; CI compilation alone cannot validate device behaviour.

# MobileGamingStreamer — Changelog

Development prerelease builds for device testing.

## 0.5.7-dev — RTMP startup lifecycle and original claymorphism

### Fixed
- Split the RTMP broadcast engine into a dedicated foreground service and the separate user-enabled microphone compatibility AccessibilityService.
- Start the RTMP foreground service directly from the successful Android screen/app capture consent result instead of relying on a broadcast receiver hosted by an AccessibilityService.
- Removed the stop-time call that disabled the AccessibilityService, preventing the next stream start from targeting an unavailable receiver.
- Keep the accessibility service free of active event subscriptions and without window-content retrieval.

### Updated
- Restored a more pronounced claymorphism visual language: warm layered surface gradients, deeper soft shadows, larger sculpted corners and rounded control geometry.
- Updated in-app and HTML privacy wording to describe the no-event-subscription accessibility implementation.
- Bumped development build to 0.5.7 (versionCode 14).

### Device validation still required
- GitHub Actions compilation does not prove RTMP connection, capture of a selected app window, or BGMI microphone/game/teammate audio on the Galaxy S25 Ultra.
- The AccessibilityService declaration remains in the APK. Play Protect's sideload block cannot be bypassed by changing the install UI; Google review/appeal or compliant Play distribution is required.

## 0.5.6-dev — About, settings, Claymorphism theme and Play readiness

### Added
- Added About page crediting Juber Saleem as developer and BGMI / PUBG Mobile streamer.
- Added Settings navigation with persistent Classic and Claymorphism theme choices.
- Added a shared theme palette and original claymorphism surfaces with pronounced rounded forms, raised highlights and deeper 3D shadows and a warm light palette.
- Added in-app Privacy Policy and Terms & Conditions pages.
- Added standalone HTML legal pages under docs/ for future GitHub Pages hosting.
- Added explicit affirmative Accessibility disclosure before launching Android Accessibility settings.
- Added a Play Store readiness audit covering AccessibilityService, foreground-service declarations, privacy hosting, Data safety, store listing, signing and test requirements.
- Updated version to 0.5.6 (versionCode 13) and corrected APK artifact/release filename mismatches.

### Review notes
- targetSdk and compileSdk are API 36.
- The AccessibilityService is not declared as an accessibility tool. It has no active event subscriptions and has canRetrieveWindowContent=false; its Play Console declaration and review video are still required.
- The debug APK is for device testing only. A signed release Android App Bundle and protected upload key are still required for Google Play.
- The legal HTML files are not publicly hosted until GitHub Pages is enabled and verified.
- Google Play approval, BGMI audio capture, teammate voice capture, and long-session stability are not certified by this source update.

## 0.5.4-dev

- Replaced the microphone-only RtmpDisplay audio setup with RootEncoder GenericStream + ScreenSource.
- Added selectable microphone-only, internal game/device playback-only, and microphone + game audio modes.
- Default audio mode is BALANCED. Mixed mode uses MediaProjection AudioPlaybackCapture and separate microphone/internal volume levels.
- Enabled platform echo cancellation and noise suppression for microphone and mixed capture paths; game-only mode avoids applying voice processing to game playback.
- Fixed portrait output dimensions to standard 9:16 canvases at 720x1280, 1080x1920 and 1440x2560. Landscape output uses standard 16:9.
- Portrait mode now uses a centered 16:9 gameplay viewport, leaving black upper/lower bands reserved for custom graphics and templates.
- Added stream resource release on stop/disconnect to avoid stale projection/audio capture sessions.
- Android playback capture is subject to the target game's capture policy; some games may prohibit it. Device testing is required to confirm BGMI audio.

## 0.5.3-dev

- Unified the app and floating-control stop actions through the RTMP service stop entry point.
- Floating controls now listen for RTMP state changes and close when the broadcast stops or fails.
- Added resolution/frame-rate-aware starting bitrate profiles: 720p (4/6 Mbps), 1080p (8/12 Mbps), and 1440p (14/18 Mbps).
- Expanded live bitrate adjustment up to 18 Mbps.
- These changes require physical-device testing; they do not yet prove microphone or internal game-audio capture.

# MobileGamingStreamer — Changelog

Development prerelease builds for device testing.

## 0.5.1-dev — Stream startup and floating bubble fixes

### Fixed
- Initialize the microphone/AAC audio encoder before starting RTMP. RootEncoder has a documented screen-only capture issue where video frames can stall when no audio source is initialized.
- Request microphone permission before starting a live session and declare the microphone foreground-service type.
- Request Android Display over other apps permission before live capture so the recorder-style floating bubble can appear automatically after the user approves screen capture.
- Use a compatible AAC encoder selection rather than forcing the audio encoder to hardware-only.

### Added
- Compact draggable app-icon bubble appears after the user approves screen capture.
- Bubble dims after 3.5 seconds and expands to controls only when tapped.
- Floating overlay windows use Android FLAG_SECURE so their contents are excluded from normal screenshots and screen capture.
- Expanded controls retain stop-stream and live bitrate presets.
- Main status now identifies screen video plus microphone audio.

### Platform notes
- Android still requires explicit screen-capture consent for every new MediaProjection session. The app cannot silently start capturing Chrome or BGMI without the system confirmation.
- The YouTube video ID is not transmitted in RTMP server URLs or stream keys. Automatic chat discovery requires YouTube account OAuth and the YouTube Live Streaming API; a manual ID is retained until that integration is configured.
- Microphone capture is implemented. Internal game playback audio and mixing with microphone are still a separate milestone; Android may restrict capture for some apps.
- The overlay is marked secure to keep its UI out of captured video. Verify this on the target Samsung device, as OEM capture behaviour can vary.

## 0.5.2-dev — RTMP pipeline and GO LIVE flow corrections

### Fixed
- Correct the audio codec policy: keep hardware H.264 for screen video but select the first compatible AAC encoder instead of forcing hardware audio encoding. The earlier 0.5.1 changelog described this intended policy, but the service implementation had not actually applied it.
- Keep the session in a connecting state until RootEncoder reports a positive outgoing bitrate. Connecting or encoder-ready callbacks no longer mark the broadcast LIVE.
- Add a 25-second startup watchdog. If no outgoing bitrate is detected, stop the failed session and publish a visible failure reason instead of leaving a false live state.
- Use a steady 15 FPS force-render setting for MediaProjection so static screens continue producing video frames.
- Calculate the requested output from physical display metrics rather than the launcher activity's window bounds. This avoids choosing portrait dimensions merely because the user launched GO LIVE from the portrait dashboard before selecting a landscape game.

### Changed
- Make **GO LIVE • SELECT APP** the first screen and the single broadcast entry point. After the user taps it, the app requests Android screen-sharing consent; the user selects BGMI or another app in the system picker.
- Make overlay permission optional for streaming. The floating bubble is started only after outgoing video bitrate is confirmed, and only if overlay permission is granted.
- Fix the return-from-Settings flow for enabling floating controls so it no longer accidentally launches a new screen-capture session.
- Update README to describe implemented functionality and remaining device-validation work accurately.

### Validation status
- GitHub Actions compilation is required for this commit.
- Actual RTMP delivery, Samsung MediaProjection app-window capture, overlay exclusion, and microphone capture still require physical-device testing. A successful APK build is not a streaming certification.
