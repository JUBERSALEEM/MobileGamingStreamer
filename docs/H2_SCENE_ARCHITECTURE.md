# H2 Scene Architecture: First Compositor (Passthrough)

Investigation record + implementation design for the first Android scene
source. H1 facts are not repeated here except where H2 extends them — see
`docs/H1_SCENE_OWNERSHIP.md` for the base ownership map. Every library claim
below was verified against exact RootEncoder 2.8.1 sources.

## 1. Pipeline: current vs H2

Current production (unchanged default):

```
MediaProjection ──► ScreenSource VD ──► Surface(library ST) ──► library EGL ──► encoder Surface ──► RTMP
```

H2 opt-in (`scene_source` extra, default false):

```
MediaProjection ──► SceneSource VD ──► Surface(compositor input ST)
                                                  │
                          compositor EGL thread:  │  updateTexImage + OES passthrough draw
                                                  ▼
                                    Surface(library ST) ──► library EGL (unchanged) ──► encoder ──► RTMP
```

The library downstream of its SurfaceTexture — GL draw, viewport,
orientation, force-render, fps limit, H.264, RTMP — is untouched and unaware.
The only production-path difference is WHO produces into the library ST.

## 2. Ownership (H2 additions; H1 map still holds)

| Resource | Owner | Notes |
|---|---|---|
| `MediaProjection` session | Service (unchanged) | `SceneSource` holds a ctor reference only; never stops it |
| VD + VD-attached input Surface | `SceneSource` | Created in `start()`, released first in teardown |
| Compositor input ST + OES texture | `SceneCompositorGL` (GL thread) | Texture id from compositor context; ST callbacks on GL handler |
| Compositor EGL (display/context/surface/program) | `SceneCompositorGL` (GL thread) | Separate context, NO sharing (see §4) |
| Output `Surface(library ST)` wrapper | `SceneSource` | Released in teardown; the library ST itself is never touched |
| GL HandlerThread | `SceneSource` | Quit last; stale callbacks guarded by thread identity (§8) |
| Projection callback (source's own) | `SceneSource` | Registered in `start()`, unregistered in teardown; flips the REVOKED guard only |
| Lifecycle/frame/release decisions | `SceneCompositorController` (pure Kotlin) | Shell executes; controller decides — all decisions unit-tested |

`SceneSource` deliberately does NOT use the library's static
`MediaProjectionHandler` holder (only `ScreenSource` reads it); the held
ctor reference avoids clobbering global state.

## 3. VideoSource seam contract (exact 2.8.1, verified)

- `init(w,h,fps,rotation)` (concrete) stores params, calls `create()`.
- `create()`: `ScreenSource` throws `IllegalArgumentException("width and
  height values must be divisible by 2")` on odd dims — mirrored exactly.
- `start(libraryST)`: produce into the given ST. Called once per
  `startSources()` (guarded by `isRunning()`); `changeVideoSource()` (unused
  by this app) may also call it.
- `stop()` / `release()`: `release()` calls stop-then-release paths;
  `ScreenSource.release()` is a no-op with all teardown in `stop()`.
- `isRunning()`: `ScreenSource` = VD != null — mirrored (VD is the ground
  truth of "producing").
- `getOrientationConfig()`: `isPortrait = (rotation==90||rotation==270) !=
  (height>width)`, camera 0, forced NONE — mirrored formula for parity.
- `GenericStream(context, checker, videoSource: VideoSource, audioSource)`:
  any `VideoSource` plugs the constructor seam.

## 4. EGL model: separate context, no sharing

The compositor creates its own EGL14 display/context (ES2, RGBA8888 +
RECORDABLE, pbuffer-capable config chooser mirroring `SurfaceManager`),
window surface on `Surface(libraryST)`, and OES program. Sharing was
considered and REJECTED: the library's `EGLContext` is private with no
sharing API, so sharing is impossible without reflection hacks. Separate
contexts communicating through BufferQueues (`Surface`/`SurfaceTexture`
pairs) is the standard Android pattern — identical in kind to VD→ST and
ST→MediaCodec links already in this pipeline — and needs no
cross-context synchronization: each context is current on exactly one
thread, and BufferQueue handles producer/consumer fencing.

## 5. Frame pipeline

Per arrival (all on the GL thread): guard check (`isAcceptingFrames`, else
drop) → `updateTexImage()` → capture `timestamp` + sequence →
`controller.onFrameArrival` (invalid → counted drop, skip draw; full →
counted drop, skip draw) → fullscreen OES draw at output viewport →
`eglPresentationTimeANDROID(capture timestamp)` → `swapBuffers` →
`complete(handle)` in `finally` (slot hygiene on every path).

- Timestamps: true capture nanoseconds from the input ST, so the library's
  `glTimestamp` receives the same clock domain as the direct path.
- Backpressure: ST-level coalescing (producer overwrites before latch) is
  unobservable by design — same as the direct path. The window (capacity 2,
  synchronous offer→draw→complete) measures OUR handoff: non-zero drops =
  GL-thread saturation signal, never silent.
- Memory: no queues, no per-frame allocations in steady state except one
  tiny completion handle (~60/s, GC-trivial); matrices/buffers reused.
- Frame rate into the library equals the direct path (every VD frame);
  library fps-limit/force-render behave identically.

## 6. MediaProjection lifecycle + revocation

- Bound at construction (ctor param) — hence CREATED+revoke→REVOKED.
- Revocation before start: instance can never start; service rebuilds.
- Revocation while active: source callback flips REVOKED (frames rejected
  immediately); service teardown (`stopBroadcast` → `display.release()` →
  `stop()`+`release()`) performs actual teardown. No second authority: the
  source never stops the stream itself.
- Fresh projection per session always (service-owned, unchanged).
- Teardown order (fixed, each exactly-once): VD → output Surface → input
  ST/Surface → EGL → projection callback → GL thread. Rationale: stop the
  producer first; input ST (holds compositor-context texture) dies before
  context destruction; thread quits last. Order is controller-enforced and
  unit-tested with recording fakes.

## 7. Failure handling (no second recovery authority)

- EGL/VD/surface init failure in `start()`: partial teardown, machine→FAILED,
  rethrow — service `stopBroadcast(UNABLE_TO_START…)` via the existing path
  (same as encoder-prepare failure today).
- Per-frame exception (GL error, abandoned ST, swap failure): log, machine→
  FAILED (via `onRenderFailure`), slot completed, output freezes. A frozen
  source manifests as a video stall, which the EXISTING watchdog/stall path
  handles — renderer death never invents recovery.
- Teardown races (stale callback after stop): state check distinguishes
  teardown-race (Log.w, expected) from genuine failure (Log.e + FAILED).
- `release()` is total and idempotent; double-stop is a no-op; start after
  release/revoke/fail throws or rejects; every teardown action is null-safe
  and exception-isolated (service-teardown style), so teardown always runs
  to completion.
- Library leniency note: `GlUtil.checkEglError/checkGlError` are no-ops
  unless `debugMode`; library swap/make-current failures only Log.e. H2 is
  strict at init (throw) and library-lenient per frame (log + stall path).

## 8. Thread model + race analysis

Two threads touch the source: caller (service main: `start/stop/release`,
VD create) and GL (`MGS-SceneGL`: EGL, ST callbacks, draws, EGL teardown
via latch). Controller fields are plain (documented single-thread
confinement); cross-thread contact is analyzed, not synchronized:

- `requestStop()` (main) vs in-flight draw (GL): worst case is one extra
  frame attempt against tearing-down EGL → caught → state!=STARTED → Log.w.
  Benign by construction.
- Restart race (old GL thread's queued callback during new start): stale
  callbacks are impossible by serialization, not by identity check. The
  listener is registered with the GL handler, so every callback runs ON the
  GL thread; teardown nulls the listener, drains the handler queue, and then
  runs EGL destruction as a posted runnable that necessarily executes AFTER
  any in-flight callback on the same looper. A fresh start creates a new
  compositor + thread only after the old one fully tore down, so a stale
  callback can never falsely FAIL a healthy restart. (`quitSafely()` +
  queue-drain hygiene on top.)
- Latch-awaited GL init/teardown (5 s timeouts) serialize setup order;
  timeout fails the attempt loudly, never hangs the service.

## 9. Orientation/resolution parity

`SceneSourceLayout` (pure, unit-tested) mirrors `ScreenSource` +
`prepareVideo` exactly: rotation-aware VD/input size, even-dimension rule,
output (encoder) size with the 90/270 swap, and the `getOrientationConfig`
portrait formula. For the production case (rotation 0): input = output =
profile size, 1:1 passthrough. The library ST buffer size, viewport,
`setStreamIsPortrait`, and force-render cadence are driven by the unchanged
service code.

## 10. Service seam (explicit opt-in, default unchanged)

`RtmpBroadcastService` reads `EXTRA_SCENE_SOURCE` (default false) and
selects `SceneSource(mediaProjection, dpi)` vs `ScreenSource(...)`, logging
the active source. `MainActivity` forwards its own launch-intent extra (one
line, default false). Production behavior (launcher tap, no extra) is
byte-identical; device validation runs via:

```
adb shell am start -n com.jubersaleem.mobilegamingstreamer/.MainActivity --ez scene_source true
```

then GO LIVE in the app. Normal launch → `ScreenSource` again. No UI, no
prefs, no new permissions.

## 11. Device validation (user-run; S25 Ultra, passthrough only)

Maintainer must verify before any layer work: (1) GO LIVE with the extra,
confirm Logcat `Video source: SceneSource compositor passthrough`;
(2) orientation/visual parity vs direct path (same scene, compare);
(3) game + mic audio unchanged; (4) floating controls; (5) stop/restart;
(6) revocation (swipe-away projection / Stop) → clean terminal state, no
restart-on-dead-token; (7) ≥10-min sustained stream, watch memory/Logcat
for EGL/Surface/encoder/lifecycle errors; (8) `droppedFrames` stays 0 in
steady state (saturation signal otherwise — surfaced via Logcat counter
line on stop).

## 12. Risks + deferred

- GLSL compiles only on-device (string resource in code): kept textbook
  minimal; S25 gate proves it. A shader failure fails `start()` loudly.
- Present-time/swap semantics verified on-device, not in CI.
- `resize()` not implemented (unused by the service); multi-resolution
  record path untested (app never records).
- Camera/media/text/layers/scenes remain future phases; no dependencies
  added; no NDK.
