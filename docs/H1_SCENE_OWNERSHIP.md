# H1 Scene Architecture: Ownership, Frame Pipeline, and H2 Boundary

H1 investigation record for the MobileGamingStreamer scene engine. Every claim
below cites the exact source inspected (app code at H1 HEAD, RootEncoder
2.8.1 dependency sources). No behavior is inferred from class names alone.

## 1. Current production pipeline (unchanged by H0/H1)

```
Service MediaProjection ──► ScreenSource VirtualDisplay ──► Surface(SurfaceTexture)
      │                              │                                │
      │                         library-owned                    library-owned
      │                         HandlerThread                    (MainRender ► CameraRender)
      │                                                              │
      │                     GlStreamInterface (GL executor thread):    │
      │                     onFrameAvailable ► fps limit ► draw      │
      │                                                              ▼
      │                                              VideoEncoder input Surface
      │                                              (codec.createInputSurface)
      │                                                              │
      └─────────────────── GenericStream orchestration ─────────────► RTMP
```

## 2. Ownership map (source-verified)

| Resource | Owner | Evidence |
|---|---|---|
| `MediaProjection` session | App: `RtmpBroadcastService` | Created L419, stored `activeProjection` L421, callback registered L436, released in `releaseProjection()` L630-636 (unregister + `stop()`, exception-isolated, null-out idempotent) |
| Static projection holder | Library: `MediaProjectionHandler.mediaProjection` | `object MediaProjectionHandler { var mediaProjection }`; overwritten by every `ScreenSource` init — only one screen source per process is safe |
| `VirtualDisplay` | Library: `ScreenSource` | Created in `ScreenSource.start()` via the static holder; released in `ScreenSource.stop()`; `isRunning()` = VD != null |
| `Surface(SurfaceTexture)` wrapper | Library: `ScreenSource` | Created in `start()`, released in `stop()`; failure path releases surface + quits thread before rethrow |
| Source handler thread + projection callback | Library: `ScreenSource` | Own `HandlerThread(TAG)` per start; own `MediaProjection.Callback` registered/unregistered on it |
| `SurfaceTexture` instance | Library: `MainRender` via `CameraRender.surfaceTexture` | `MainRender.kt` L181-182; exposed as `GlStreamInterface.getSurfaceTexture()`; frame listener attached in `GlStreamInterface.start()` |
| EGL contexts + GL thread | Library: `GlStreamInterface` | Single-thread executor, `SurfaceManager` EGL setup, `MainRender.initGl` in `start()`; all surface managers + render released in `stop()` |
| Encoder input `Surface` | Library: `VideoEncoder` | `codec.createInputSurface()` in SURFACE-mode `prepare()`; released in `stopImp()`; attached to GL via `addMediaCodecSurface` |
| Orientation decision | App service + source config | Service computes `portrait` (L408-412) → `setStreamIsPortrait(portrait)` (L499); `prepareVideo` applies rotation-gated encoder size, `setCameraOrientation(270)` for rotation 0, and `ScreenSource.getOrientationConfig()` |
| Sensor rotation | Library, INERT in this app | `SensorRotationManager` callback acts only when `autoHandleOrientation && shouldHandleOrientation`; the service never enables it (default false), so device rotation never perturbs the stream |
| Session ordering / recovery | App: service + router + driver | `beginSession()` before releasing previous resources (L223); teardown everywhere is `display.release()` THEN `releaseProjection()` (`stopBroadcast` L583-589, disconnect path L340-350, `onDestroy` L735-742) |

`ScreenSource.release()` is a deliberate no-op: `stop()` performs all
teardown. `stop()` is null-safe across VD/surface/callback.

## 3. The sanctioned custom-source seam (exact 2.8.1 API)

`VideoSource` (abstract) members:

- Mutable fields: `surfaceTexture`, `created`, `width`, `height`, `fps`, `rotation`
- `init(w, h, fps, rotation): Boolean` (concrete: stores params, calls `create`)
- `protected abstract create(...): Boolean` — `ScreenSource` rejects odd
  width/height here (`IllegalArgumentException`, must be divisible by 2)
- `abstract start(surfaceTexture: SurfaceTexture)` — receives the
  LIBRARY-owned SurfaceTexture; the source must produce into it
- `abstract stop()`, `abstract release()`, `abstract isRunning(): Boolean`
- `open getOrientationConfig()` — feeds `setOrientationConfig` / portrait logic

Entry points: the `GenericStream` constructor (current `ScreenSource` path) or
`StreamBase.changeVideoSource(source)` (L399-423), which stops the old source,
clears the SurfaceTexture, starts the new source on the SAME SurfaceTexture,
releases the old source, and swaps — retrying the old source if the new one
fails to start. Any H2 scene source enters through one of these two doors;
there is no third integration point.

## 4. Session lifecycle ordering (service, verified)

- Start: gate `beginSession()` → release previous `display` + projection →
  build new projection/stream → `prepareVideo` profile loop → `prepareAudio` →
  GL orientation/force-render → `startStream`.
- Stop (all paths: user stop, stall, disconnect, auth failure, startup
  failure): `display.release()` (library tears down sources, GL, encoders)
  then `releaseProjection()` (unregister service callback, `projection.stop()`).
- Revocation: `MediaProjection.Callback.onStop` → token-routed
  `onProjectionStop` → `stopBroadcast(PROJECTION_ENDED_MESSAGE)` — full session
  teardown; restart rebuilds everything with a fresh projection. This is why
  the H0/H1 lifecycle machine models revocation as terminal-per-instance
  (`REVOKED`): it mirrors production reality.
- `onDestroy`: terminal invalidation FIRST (stale async `onDisconnect` cannot
  overwrite terminal state), then the same release order.

## 5. Why H1 ships no EGL/GL compositor (stop-condition record)

A scene source must consume MediaProjection frames itself (own VD → own
SurfaceTexture) and render the composed output into the library-provided
SurfaceTexture. That render step needs an EGL context the library does not
share (`SurfaceManager` is private; no context-sharing API exists), so H2
needs its own EGL setup, compositor thread, and teardown ordering against
`ScreenSource.stop()`-style semantics — all unverifiable without a device
(JVM + CI cannot execute EGL). Per the H1 mandate, that implementation is
DEFERRED, not improvised. H1 instead fixes everything verifiable now:

- H0 corrections: composition immutability (unmodifiable view), terminal
  revocation (`REVOKED` state).
- Handoff semantics in tested code: `SceneFrameMetadata` (sequence + capture
  timestamp), `SceneFrameWindow` (bounded in-flight, drop-newest, exact
  accepted/dropped accounting), `SceneReleaseGuard` (exactly-once teardown).
- This ownership record, so H2 implements against facts, not guesses.

## 6. Risks and open H2 requirements

1. Single static projection holder: two concurrent screen sources corrupt each
   other. H2 must keep exactly one active source (as today).
2. `changeVideoSource` failure path restarts the OLD source; an H2 source that
   fails `start()` after partial acquisition must leave no VD/surface/thread
   behind, or the retry inherits garbage.
3. SurfaceTexture producer/consumer threading: frames arrive on the GL
   handler; the H2 compositor thread must serialize `updateTexImage`,
   draws, and `stop()` teardown with no use-after-release.
4. Timestamp clock must be chosen explicitly (SurfaceTexture vs elapsed) and
   documented in the H2 adapter; the metadata contract only requires
   non-negativity.
5. `SPS/PPS`, rotation, and portrait behavior of a custom source must be
   verified on-device against the current passthrough output (S25), not
   assumed from the seam signature.
6. No mid-session encoder recreation is permitted by this architecture:
   orientation/canvas changes must be absorbed by the compositor, never by
   rebuilding the encoder (matches the H0 geometry contract).

## 7. H1 boundary (what H1 did and did not do)

Did: H0 corrections A + B with tests; `scene/SceneFrameHandoff.kt` with
`SceneFrameHandoffTest`; this document. Did not: any EGL/GLES/SurfaceTexture/
MediaCodec/VirtualDisplay code; any `GenericStream` wiring; any change to
services, transport, retry, watchdog, recovery, diagnostics, audio, overlay,
accessibility, profiles, workflows, or dependencies. The app behaves
identically before and after H1; `ScreenSource` remains the only production
source.
