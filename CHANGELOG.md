# MobileGamingStreamer — Changelog

All builds published from the `main` branch are development prereleases. They are for device testing and are not production releases.

## 0.2.0-dev — UI and automatic APK releases

### Added
- Automatic GitHub Release for every successful build from `main`.
- Debug APK attached to each prerelease, as well as a downloadable Actions artifact.
- Release notes sourced from this changelog, with GitHub-generated commit notes appended.
- Redesigned dark gaming-studio dashboard with neon-lime accents and compact status cards.
- Clear module readiness indicators for capture, microphone, encoder, and broadcast.

### Notes
- Screen capture is still a validation feature. Frames are not encoded, recorded, or broadcast.
- Audio, MediaCodec encoding, RTMP/RTMPS, and overlays are not implemented yet.
- Pull-request builds produce an Actions artifact but do not publish a Release.
