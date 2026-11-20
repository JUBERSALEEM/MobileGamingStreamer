# MobileGamingStreamer

An Android-first mobile livestreaming app. The first device-validation target is the Samsung Galaxy S25 Ultra running BGMI. This is a development build; real-device streaming stability has not yet been certified.

## Current development version

- App version: **0.5.23-dev**
- Android version code: **30**
- Installable test APK: **MobileGamingStreamer-0.5.23-release.apk** (published by CI as a pre-release; pull-request runs upload an Actions artifact only)
- Releases page: https://github.com/JUBERSALEEM/MobileGamingStreamer/releases

## Current implementation

- Kotlin and Jetpack Compose Android app.
- One **GO LIVE • SELECT APP** flow: enter the RTMP server/key, grant microphone permission if requested, then use Android's system screen-sharing picker to select the screen or an app.
- RootEncoder GenericStream pipeline with H.264 screen video and selectable microphone-only, internal playback-only, equal-level mix and voice-boosted mix audio presets. Actual BGMI game/teammate voice capture remains device- and game-policy-dependent and is not yet certified.
- Foreground broadcast service with persistent notification, stop action, connection status and outgoing-bitrate reporting.
- The app only reports LIVE after a positive outgoing bitrate callback. A 25-second startup watchdog reports a failure if video bitrate never appears.
- Optional draggable floating controls; the bubble is launched after outgoing video bitrate is confirmed. Overlay permission is not required to start streaming.
- Floating controls provide stop, bitrate and separate stream-audio mute controls. The local speaker-volume control remains separate. The stream-audio mute applies to microphone-only and mixed sources, not internal-game-audio-only mode.
- Android's DEFAULT microphone source is used so platform echo cancellation/noise suppression can attach where supported; a connected headset microphone is selected as the preferred input when Android exposes one. On top of that, an on-device studio voice chain shapes the microphone before mixing: noise gate, automatic gain, soft-knee compressor and presence EQ, with live AEC/NS/voice status shown on the Audio page. Actual effect performance must be tested on the device.
- YouTube chat currently requires a manually entered video ID; automatic account discovery is not implemented yet.
- Internal playback capture and microphone mixing are implemented in the pipeline, but BGMI gunshot audio and teammate voice inclusion still require physical-device verification. Android playback capture policies may exclude some game/voice-chat audio.

## App identity, appearance and legal

- About screen credits **Juber Saleem** as developer and identifies the app as an independent mobile-streaming project by a BGMI / PUBG MOBILE streamer.
- The whole app uses one claymorphism design language governed by a single token contract (`docs/DESIGN_TOKENS.md`): raised clay cards, recessed fields, sculpted dialogs, a clay navigation dock, truthful status tones and a dark graphite floating-control overlay. There is no second appearance theme.
- Broadcast transitions announce once each for TalkBack; every control meets the 48dp touch target.
- Privacy Policy and Terms & Conditions are available in-app and as HTML pages under `docs/`.
- The public legal-page URL is not live until GitHub Pages is enabled for the repository using the `docs/` folder.

## Install the latest test APK from your phone

1. Open the [GitHub Releases page](https://github.com/JUBERSALEEM/MobileGamingStreamer/releases).
2. Select the newest entry marked **Pre-release**. Its title starts with **MobileGamingStreamer v0.5.23**.
3. Under **Assets**, tap **MobileGamingStreamer-0.5.23-release.apk**. Do not download the source-code ZIP files.
4. If GitHub displays an asset download warning, choose Download anyway, then open the downloaded APK and allow your browser/file manager to install unknown apps if Android asks.
5. Install the APK. The development-signed package is separate from a future Play Store package and may install alongside another package variant.

## How builds and releases work

- Every push to `main` or `fix/stream-key-claymorphism` builds the APK, uploads a version-labelled Actions artifact, and publishes a GitHub **pre-release** with the APK attached.
- Pull-request checks build and upload an Actions artifact only. They do not publish a Release; this prevents unmerged PR code from being presented as an installable release.
- A manual **Run workflow** from the Actions tab also builds and publishes a pre-release for the selected branch.
- The workflow reads `versionName` and `versionCode` directly from `app/build.gradle.kts`, names the APK automatically, and extracts the matching top section from `CHANGELOG.md` for the release description. It fails if the matching changelog section is missing.
- The APK is a development-signed device-testing build (pinned `androiddebugkey` identity, verified by CI against the APK certificate), not a production-signed Google Play release.

If a build succeeds but no release appears, check whether the run event was **pull_request**. PR runs intentionally do not publish; use a branch push or manually run the workflow on the development branch.

## Build locally

Requirements: JDK 17, Android SDK Platform 36, Android Gradle Plugin 8.13.2 / Gradle 8.13.

```bash
gradle :app:assembleDebug
```

APK output: `app/build/outputs/apk/debug/app-debug.apk`.

## Google Play release status

This repository is not yet production-ready for Google Play. The current workflow produces a debug APK for phone testing, not a signed release AAB. The AccessibilityService and mediaProjection/microphone foreground-service declarations require accurate Play Console declarations and review videos. See [PLAY_STORE_READINESS.md](PLAY_STORE_READINESS.md). The developer must also enable GitHub Pages, verify the public privacy URL, complete Data safety and store listing declarations, provide a support email, and complete real-device testing. No Play approval is guaranteed.

## Product principles

- Free-first development using reviewed open-source dependencies.
- Explicit user consent and Android-compliant foreground operation.
- Separate capture, audio, encoding, transport and UI responsibilities.
- No public release until long-session stability, privacy, compatibility and licensing checks pass.
