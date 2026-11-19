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
- Actual RTMP delivery, Samsung MediaProjection app-window capture, overlay exclusion, and microphone capture still require physical-device testing. A successful build is not a streaming certification.
