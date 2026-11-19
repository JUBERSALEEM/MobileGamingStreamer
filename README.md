# MobileGamingStreamer

An Android-first mobile livestreaming app. The first device-validation target is the Samsung Galaxy S25 Ultra running BGMI. This is a development build; real-device streaming stability has not yet been certified.

## Current implementation

- Kotlin and Jetpack Compose Android app.
- One **GO LIVE • SELECT APP** flow: enter the RTMP server/key, grant microphone permission if requested, then use Android's system screen-sharing picker to select the screen or an app.
- RootEncoder-based RTMP display pipeline with H.264 video and microphone/AAC audio preparation.
- Foreground broadcast service with persistent notification, stop action, connection status and outgoing-bitrate reporting.
- The app only reports LIVE after a positive outgoing bitrate callback. A 25-second startup watchdog reports a failure if video bitrate never appears.
- Optional draggable floating controls; the bubble is launched only after outgoing bitrate is confirmed. Overlay permission is not required to start streaming.
- Floating controls provide stop and bitrate controls. YouTube chat currently requires a manually entered video ID; automatic account discovery is not implemented yet.
- Internal game-audio capture/mixing is not implemented yet.

## Important Android behaviour

Android requires the user to approve each MediaProjection session. GO LIVE opens the system picker; the user must choose the display or app and confirm. The app cannot silently choose or capture BGMI. App-window capture size/orientation can vary by Android version and device, so this build still requires testing on the target Samsung phone.

The floating bubble needs the Android **Display over other apps** permission. This permission is optional and does not block the RTMP stream. The overlay uses a secure window flag to request exclusion from screen capture; verify the result on the target device.

## Install the latest test APK from your phone

1. Open https://github.com/JUBERSALEEM/MobileGamingStreamer
2. Open **Releases** and select the newest development build.
3. Download the APK asset named **MobileGamingStreamer-0.5.2.apk** (or the newer version shown).
4. Install it. Android may ask you to allow your browser or file manager to install unknown apps.
5. Open the app, enter your RTMP server URL and stream key, and tap **GO LIVE • SELECT APP**.
6. In Android's screen-sharing picker, select BGMI or the desired app and confirm.
7. Check the persistent MobileGamingStreamer notification. It should progress through encoder preparation and RTMP connection; the app should report LIVE only after outgoing bitrate is detected.

If startup fails, record the exact status shown in the app and notification. Do not treat a successful APK build as proof that live streaming works.

Every successful push to `main` publishes a GitHub prerelease with the APK attached. Pull-request builds create a downloadable Actions artifact but do not publish a Release.

## Build locally

Requirements: JDK 17, Android SDK Platform 36, Android Gradle Plugin 8.13.2 / Gradle 8.13.

```bash
gradle :app:assembleDebug
```

APK output: `app/build/outputs/apk/debug/app-debug.apk`.

## Product principles

- Free-first development using reviewed open-source dependencies.
- Explicit user consent and Android-compliant foreground operation.
- Separate capture, audio, encoding, transport and UI responsibilities.
- No public release until long-session stability, privacy, compatibility and licensing checks pass.
