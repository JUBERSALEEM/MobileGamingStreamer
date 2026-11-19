# MobileGamingStreamer

An Android-first livestreaming application for mobile gamers. The first development and validation target is the Samsung Galaxy S25 Ultra running BGMI; the long-term goal is a stable app that can be tested with a wider range of Android devices before public release.

> **Early development:** This repository is a foundation, not a production streaming app. The current capture milestone requests Android screen-capture consent and starts a foreground MediaProjection session. It does not encode, record, or broadcast video yet.

## Current status

- Native Android app using Kotlin and Jetpack Compose.
- Initial dark streaming dashboard.
- User-approved MediaProjection permission flow.
- Foreground screen-capture service with persistent notification and stop action.
- GitHub Actions workflow to build a debug APK.

See [the roadmap](docs/ROADMAP.md) for milestones and acceptance criteria.

## Build

Requirements:
- JDK 17
- Android SDK Platform 36
- Android Gradle Plugin 8.13.2 / Gradle 8.13

Build a debug APK:

```bash
gradle :app:assembleDebug
```

The APK is generated at `app/build/outputs/apk/debug/app-debug.apk`.

## First device test

1. Install the debug APK on an Android 10+ device.
2. Open the app and tap **Start capture**.
3. Approve Android's screen-capture prompt.
4. Confirm the persistent capture notification appears.
5. Tap **Stop** in the app or notification and confirm the notification disappears.

This milestone creates a capture surface only. It does not save frames or send them anywhere.

## Product principles

- Free-first development with reviewed open-source dependencies.
- Explicit user consent and Android-compliant foreground operation.
- Separate capture, audio, encoding, transport, and UI modules.
- No public release until long-session stability, privacy, compatibility, and licensing checks pass.
