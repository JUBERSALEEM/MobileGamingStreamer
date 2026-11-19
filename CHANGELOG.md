# MobileGamingStreamer — Changelog

All builds published from the `main` branch are development prereleases. They are for device testing and are not production releases.

## 0.3.0-dev — H.264 encoder foundation and layout refinement

### Added
- Surface-input Android MediaCodec H.264 hardware encoder connected directly to MediaProjection.
- Device capability checks choose 60 FPS when supported, otherwise 30 FPS.
- Encoder output diagnostics report encoded frame rate and bitrate; encoded frames are discarded pending RTMP transport.
- Capture output is scaled proportionally to a maximum 1920-pixel dimension.

### Improved
- Dashboard respects Android safe drawing insets, preventing content from overlapping the status and navigation bars.
- Encoder module reflects active encoding during a capture session.

### Limitations
- This build does not record or broadcast video. RTMP/RTMPS transport is not implemented.
- Microphone and game audio are not implemented.
- Encoder stability and sustained performance still require physical-device testing.

## 0.2.0-dev — UI and automatic APK releases

### Added
- Automatic GitHub Release for every successful build from `main`.
- Debug APK attached to each prerelease, as well as a downloadable Actions artifact.
- Release notes sourced from this changelog, with GitHub-generated commit notes appended.
- Redesigned dark gaming-studio dashboard with neon-lime accents and compact status cards.
- Clear module readiness indicators for capture, microphone, encoder, and broadcast.

### Notes
- Screen capture was initially a validation feature. Frames were not encoded, recorded, or broadcast.
- Audio, RTMP/RTMPS, and overlays are not implemented yet.
- Pull-request builds produce an Actions artifact but do not publish a Release.
