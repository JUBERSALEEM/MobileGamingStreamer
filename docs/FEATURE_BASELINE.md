# Feature baseline — Mobile Gaming Streamer

Captured at `f691377` on `arena/239daa25-mobilegamingstreamer` (version 0.5.22,
version code 29). Every item below must remain available throughout redesign,
audio and stability work. Items marked *(fix pending)* have a known defect with
an approved repair; the feature itself must survive the repair.

## Screens and navigation (Compose)

- Studio home: hero control-centre card, live-session card with transport
  controls, notification-permission card, footer line.
- Stream workspace: RTMP destination card (server URL, private key, secure
  remember toggle), video-profile card (resolution, frame rate, orientation),
  audio-source summary card, transport card (GO LIVE / STOP LIVE).
- Audio mixer: audio-source selector (MIC, GAME, NORMAL, BALANCED), selection
  detail well, Voice Optimization status card, BGMI microphone-bridge card.
- Tools: floating-controls card, H.264 capture-test card, system-status card.
- Settings: appearance card, Legal & privacy menu (About, Privacy, Terms).
- About: product identity, approach/affiliation note, legal shortcuts.
- Legal reader: Terms & Conditions and Privacy Policy bodies, back navigation.
- Dialogs: stream-key entry, Accessibility disclosure.
- Shell: clay top bar with build badge and settings shortcut, bottom
  navigation dock (Studio, Stream, Audio, Tools, Settings), system back
  handling (detail pages return to Settings, otherwise Studio).

## Streaming and capture

- MediaProjection consent per session; GO LIVE and H.264 capture test flows.
- RTMP/RTMPS broadcast with hardware H.264 profile fallback (720p/1080p/1440p,
  30/60 FPS, portrait/landscape), live FPS listener, bitrate telemetry.
- 25-second startup watchdog and 20-second post-confirmation stall watchdog.
- Session heartbeat with stale-session recovery after process death.
- Persistent low-importance notification with stop action and live status.
- Explicit stop/cancel from app, notification and floating controls.
- Keep-streaming-on-task-dismiss: removing the app task never ends a broadcast.
- Projection revocation ends the session with a clear message.
- H.264 encoder-only capture test with no network transport.
- Fresh user-approved projection session required for every retry.

## Audio

- Audio modes MIC, GAME, NORMAL and BALANCED with mic-boosted default.
- Independent microphone and playback-capture recorders mixed after mic DSP.
- Platform AcousticEchoCanceler / NoiseSuppressor on the mic session when
  Android exposes them (skipped on the built-in-speaker BGMI route so acoustic
  teammate voice survives).
- Software voice chain: high-pass, noise gate, automatic gain, soft-knee
  compressor, presence EQ.
- PCM mixer with peak headroom protection; mic continues without playback.
- Stream-audio mute covering microphone-only, mixed and game-only paths.
- BGMI microphone compatibility bridge via the opt-in Accessibility service.
- Microphone-source policy: DEFAULT with accessibility, VOICE_RECOGNITION with
  external headset, MIC fallback; preferred-device routing.
- Live AEC/NS/voice-optimizer status reporting to the Audio screen.

## Floating overlay

- Draggable bubble with dim/brighten, drag-to-dismiss with drop target and
  persisted dismissal.
- Expanded panel: live status line, six video-bitrate presets, YouTube live
  chat (video ID entry + WebView), local speaker mute, stream-audio mute,
  stop/cancel/open-setup action, minimize and hide controls.
- Secure window flags, overlay-permission gating, status broadcast rendering.

## Permissions and privacy

- RECORD_AUDIO, POST_NOTIFICATIONS, SYSTEM_ALERT_WINDOW, INTERNET,
  MODIFY_AUDIO_SETTINGS, foreground-service permissions/types, optional
  Accessibility service, per-session MediaProjection consent.
- Android Keystore AES-GCM opt-in stream-key persistence with secure opt-out.
- Strict RTMP/RTMPS server-URL validation.
- In-app and hosted Terms & Conditions / Privacy Policy.

## Known defects approved for repair (features preserved)

- *(fix pending)* Speaker mute does not restore the previous media volume if
  the floating service is destroyed while muted, and may restore a stale value
  after the user changes volume with hardware buttons.
- *(fix pending)* Voice-chain stages need conservative retuning and broader
  automated coverage; AEC/NS attach failures are logged silently (info-level
  only on success path).
