# Google Play readiness review — Mobile Gaming Streamer 0.5.6

Review date: 20 November 2026. This is a code-level readiness checklist, not a Play approval guarantee.

## Implemented in this repository
- targetSdk and compileSdk are API 36; this meets the current Android 16/API 36 target requirement for new apps and updates submitted from 31 August 2026.
- In-app privacy policy and terms screens.
- Standalone HTML privacy and terms pages under docs/ for GitHub Pages hosting.
- About screen attributes development to Juber Saleem and identifies the project as independent, not affiliated with BGMI/PUBG MOBILE/Krafton/Tencent/Google.
- Accessibility service has canRetrieveWindowContent=false and only subscribes to window-state events; no UI inspection or automation is implemented.
- Added a separate in-app accessibility disclosure with explicit affirmative consent before opening Android Accessibility settings.
- Runtime screen-capture consent remains controlled by Android MediaProjection.
- Debug APK workflow filename mismatch corrected for 0.5.6.

## Must be completed by the developer in Play Console
1. AccessibilityService declaration: this is not an accessibility tool. Complete the non-accessibility-tool declaration accurately, explain the narrow microphone-concurrency use, and provide the required video showing the disclosure, accept and decline paths, enabling the service, and the feature in use. Google Play may reject this use case; approval is not guaranteed.
2. Foreground service declarations: declare mediaProjection and microphone, describe the user-initiated screen livestream use, explain why interruption breaks the live session, and provide a demonstration video for each applicable type.
3. Privacy policy hosting: enable GitHub Pages for the repository with the docs/ folder as the publishing source. Confirm that https://jubersaleem.github.io/MobileGamingStreamer/privacy-policy.html is publicly reachable over HTTPS before entering it in Play Console. Repository files alone do not make a public policy URL live.
4. Data safety: complete the form for the exact release and all SDKs. Screen video and microphone/game audio are sensitive. Describe user-initiated transmission to the RTMP destination and optional YouTube WebView accurately. Recheck after any SDK or network change.
5. Store listing: supply accurate title, short/full descriptions, app icon, phone screenshots, feature graphic, category, contact email, privacy URL and content rating. Do not claim audio capture works for every game or that the app is certified.
6. Testing: complete internal testing on supported Android versions and OEMs, including accessibility on/off, microphone permission denied, notification permission denied, overlay permission denied, screen-share cancellation, RTMP authentication failure, network loss, app-window capture, long sessions, thermal throttling and lifecycle/restart.
7. Release signing: current workflow produces a debug APK for device testing. A Play upload requires a properly signed release Android App Bundle (AAB), Play App Signing setup and a protected upload key. Do not upload the debug APK as the production release.
8. Developer identity and support: confirm the developer display name, contact email and support URL in Play Console. The in-app developer attribution is Juber Saleem.
9. Third-party branding: maintain the non-affiliation statement and use only artwork/screenshots you own or are licensed to use.

## Known technical/policy risks
- AccessibilityService use for microphone concurrency is policy-sensitive and requires declaration and review; an in-app disclosure is necessary but does not ensure approval.
- Microphone and MediaProjection foreground-service types require valid runtime behaviour and Play Console declarations.
- Plain rtmp:// does not encrypt stream content in transit. Prefer rtmps:// where the streaming provider supports it.
- The floating overlay uses FLAG_SECURE; verify its behaviour and ensure it does not interfere with capture or violate other apps' security flags.
- No signed release AAB, release keystore configuration, automated UI tests, privacy-policy hosting verification, Play Console declarations, store assets or physical-device test evidence are included in this repository update.