# Development release-readiness checklist

This checklist is intentionally conservative. A passing CI build is not a substitute for device, privacy, legal, or store-policy validation.

## Automated checks (CI)
- [ ] :app:testDebugUnitTest passes.
- [ ] :app:lintDebug passes with no unresolved release-blocking findings.
- [ ] scripts/verify_release_readiness.py passes.
- [ ] Debug APK is packaged with the versioned filename and changelog notes.
- [ ] Inspect the merged dependency graph and include required open-source licence notices.

## Galaxy S25 Ultra device matrix
- [ ] Fresh install, upgrade install, and app relaunch.
- [ ] Claymorphism and Classic theme, including screen sizes/font scaling.
- [ ] Overlay permission grant/deny, bubble show/hide, drag-to-dismiss, panel open/minimize.
- [ ] Confirm bitrate presets, local speaker mute, stream-audio mute and explicit Stop.
- [ ] YouTube Live Chat open/close and keyboard/overlay interaction.
- [ ] GO LIVE consent, RTMP and RTMPS ingest, invalid key, network loss, fresh-consent retry.
- [ ] MIC, GAME, NORMAL and BALANCED modes; test BGMI effects, teammate voice, echo and noise suppression.
- [ ] Confirm logs show AEC/NS on microphone only and no DSP on playback capture.
- [ ] Verify mic clarity, background/fan noise, speaker echo, headset and Bluetooth routes.
- [ ] Verify mic/game synchronization over a 30-minute stream; listen for gaps, repeats and drift.
- [ ] App backgrounding/task dismissal, screen lock, notification Stop, projection revocation.
- [ ] Rotation, selected-app capture, 30/60 FPS, thermal behaviour and sustained sessions.

## Privacy, security and platform review
- [ ] Verify the exact APK manifest and every bundled SDK/data flow.
- [ ] Confirm stream-key opt-in, encrypted persistence, opt-out deletion and behaviour after app data clear.
- [ ] Confirm no secrets, stream keys, signing keys or credentials appear in logs or repository history.
- [ ] Publish and test the hosted privacy policy and terms before store submission.
- [ ] Complete SDK licence notices, Google Play Data safety, foreground-service declarations and policy review.
- [ ] Prepare a signed release AAB with a privately stored upload key; never commit the keystore or passwords.
- [ ] Complete closed beta, crash/ANR review, support process and rollback plan.

## Release decision
Do not describe the app as production-ready or Play-approved until all applicable device, legal, policy, signing and closed-beta checks above are completed.
