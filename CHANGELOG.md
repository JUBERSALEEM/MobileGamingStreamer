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
