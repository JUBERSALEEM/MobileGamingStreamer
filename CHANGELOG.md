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
