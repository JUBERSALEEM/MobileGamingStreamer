# J2 — Real Video Layers

Status: IMPLEMENTED on this branch (MediaPlayer→ST→OES video slots, budget 2, v5-carried VIDEO, editor poster+badge; 598/598 local, K222 0, CI verified per commit message).

## 1. Investigation findings (all verified in-repo / in-AOSP)

### 1.1 Dependency inventory (app/build.gradle.kts)

- Compose BOM 2025.10.01, activity-compose 1.11.0, lifecycle-runtime-ktx 2.9.4,
  RootEncoder `library:2.8.1`, junit 4.13.2 (tests only).
- **No video decode/playback dependency.** No Media3/ExoPlayer.
- minSdk 29, compileSdk/target 36, Kotlin 2.2.20, JVM 17. No new dependency
  is necessary: every API below is framework, available since ≤ API 27.

### 1.2 Framework APIs (signatures verified against AOSP master, Nov 2026)

- `MediaPlayer.setDataSource(Context, Uri)`, `setSurface(Surface)`,
  `setLooping(boolean)`, `setVolume(float, float)`, `prepareAsync()`,
  `start/pause/stop/reset/release()`, `isPlaying()`,
  `setOnPreparedListener/OnErrorListener/OnCompletionListener/
  OnVideoSizeChangedListener` — all present in AOSP `MediaPlayer.java`.
- `MediaMetadataRetriever.setDataSource(Context, Uri)`,
  `getFrameAtTime(long)`, `getScaledFrameAtTime(long, int, int, int)`,
  `extractMetadata(int)`, `release()`,
  `METADATA_KEY_DURATION/HAS_VIDEO/VIDEO_WIDTH/VIDEO_HEIGHT`,
  `OPTION_CLOSEST_SYNC` — all present in AOSP `MediaMetadataRetriever.java`.
- `SurfaceTexture` + `setOnFrameAvailableListener(listener, handler)` +
  `updateTexImage()` + `getTransformMatrix()` — already used by
  `SceneCompositorGL` for the screen input path.

### 1.3 Renderer architecture (SceneCompositorGL, 1159 lines, read in full)

- Own EGL context (ES2, RGBA8888 recordable), own `HandlerThread`
  (`MGS-SceneGL`); all GL calls on that thread; bounded-latch rendezvous
  (5 s) for setup/teardown; teardown never throws.
- Input: `SurfaceTexture` + OES external texture fed by the MediaProjection
  VD; fullscreen OES passthrough draw + `GL_TEXTURE_2D` overlay slots
  (static bitmaps uploaded once; H9 re-upload ≤ 1 Hz).
- Overlay slots: fixed array of `MAX_STATIC_OVERLAY_LAYERS` (4); H8
  `rebuildOverlays` releases all slots then rebuilds, serialized with
  frames on the GL queue — no half-built stack can draw.
- Output: `Surface` wrapping the library ST, owned by `SceneSource`.
- Frame draws are driven by screen-input `onFrameAvailable`.

### 1.4 Encoder / service pipeline

- `SceneSource : VideoSource` owns VD + output Surface + projection
  callback; swaps via library `changeVideoSource` (H8-verified).
  Capture/encoder/RTMP/audio are untouched by scene work.
- Stop path: `stopBroadcast()` → `display.release()` → source
  `stop()/release()` → controller-ordered teardown. Destroy path:
  `onDestroy()` releases display + projection. Revocation: projection
  callback flips the REVOKED guard (frames rejected); teardown arrives
  via service stop/release.
- `publishState()` is a single function (11 call sites); per-source state
  is already read via `activeVideoSource as? SceneSource` (line 261).

### 1.5 Scene model / codec (v5, 18 fields)

- `SceneLayerKind`: TEXT/IMAGE/CLOCK/TIMER. Unknown-kind lines drop
  (`valueOf` throw → skip line), scenes survive — the H9 policy.
- `SceneOverlaySpec.of()` + `decodeLayer()` both gate per-kind content.
- Editor persists via `ScenePrefsStore` (encodings + active id);
  image picker uses `OpenDocument` + persistable read grants.

## 2. Architecture decision

**Selected: framework `MediaPlayer` → `Surface(SurfaceTexture)` → per-slot
OES external texture inside the existing compositor EGL context.**

- Zero-copy GPU path: decoder output → ST → OES quad. Never a Bitmap per
  frame; the GL thread never blocks on decode (decode runs on
  MediaPlayer-internal threads; GL only latches available frames).
- Fits the proven H2 pattern (the compositor already manages an input ST +
  OES texture + per-ST transform matrix).
- Muted = `setVolume(0,0)`; looping = `setLooping(true)`; pause/resume =
  `pause()/start()` (decoder stalls with buffers queued — no deadlock,
  no seek needed).
- Probe/poster/asset line = `MediaMetadataRetriever` (bounds + duration +
  sampled poster; never throws).

**Rejected:**

- Hand-rolled MediaExtractor + MediaCodec pump: ~300 lines of EOS/loop/
  seek/flush/buffer-starvation state machine for zero visual gain over
  MediaPlayer on the same zero-copy path. Highest risk, no benefit.
- Media3/ExoPlayer: a new dependency for file playback the framework
  already covers. Rejected per "no unneeded deps".
- Per-frame Bitmap extraction (`getFrameAtTime` loop): violates the
  no-per-frame-decode requirement and burns CPU/GPU copies.
- Live video in the editor Compose preview: would need a second decoder +
  per-frame ImageBitmap upload; the spec explicitly allows an honestly
  labeled poster preview. Poster it is.

## 3. Design

### 3.1 Model + codec (pure, JVM-tested)

- `SceneLayerKind.VIDEO`. URI reuses the `imageUri` field (same scheme
  gate ≤ 2048, same bound). FIT/FILL reuses `scaleMode` + the J1 shared
  `fillSize`/`fillCrop` math (video measures from probed dims).
- **No codec version bump (stays v5).** Rationale: a v6 bump would make
  old clients drop the *whole scene* (unknown version → null), while
  v5+VIDEO makes old clients drop only the *video layer* (unknown kind →
  skip line) and keep the scene. Strictly safer; documented + tested.
- `decodeLayer` + `of()`: VIDEO requires a valid media URI (same gate as
  IMAGE); corrupt → layer drops. `of()` keeps the URI for VIDEO kinds.

### 3.2 Playback policy (pure, JVM-tested) — `SceneVideoPlayback`

- `SceneVideoState`: NOT_LOADED, READY, PLAYING, PAUSED, FAILED.
- `SceneVideoPlaybackController`: event-driven transitions, invalid
  transitions rejected (false), never throws. Events: slot assigned,
  prepared, play/pause/resume/stop requested, error, revoked, rebuild.
  FAILED is sticky until rebuild; stop is terminal until rebuild.
- Budget: `MAX_CONCURRENT_VIDEO_LAYERS = 2`. Pure
  `SceneVideoBudget.assign(specs)` decodes the first 2 enabled VIDEO
  specs in streamable order; extras → FAILED("decoder budget reached").
  During rebuild old players stop before new ones start, so live
  decoders never exceed 2.
- Rotation: pure `applyVideoRotation(w, h, degrees)` swaps probed dims
  for 90/270° (MediaPlayer renders pre-rotated frames).
- Revoke/stop policy: revoke → pause (freeze now, teardown stops);
  stop/destroy/rebuild → stop + release. Old resources can never render
  post-replacement: slots + players release before the new build, ST
  listeners nulled on the same GL queue.

### 3.3 Decoder shell (Android, device-gated) — `SceneVideoPlayer`

- Owns one `MediaPlayer` + one `HandlerThread` (`MGS-Video-N`).
  Construction + all ops + callbacks run on that thread (MediaPlayer
  delivers callbacks on the creating thread's Looper).
- API: `prepare(uriString, surface, listener)` (async, never throws),
  `pause()`, `resume()`, `stopAndRelease()` (bounded rendezvous, never
  throws; always quits its thread, even on timeout — the release op is
  queued before quit so a wedged call still self-cleans).
- Listener (called on the player thread; the compositor re-posts to GL):
  `onPrepared(size)`, `onVideoSize(w, h)`, `onError(what, extra)`,
  `onCompletion()` (looping makes this a no-op sanity log).
- Any failure → listener `onError` → compositor marks slot FAILED,
  releases the player, draws nothing (transparent). The stream never
  dies for a video failure.

### 3.4 Probe shell (Android, device-gated) — `SceneVideoProbe`

- `probeSize(resolver, uri): SceneSizePx?` — HAS_VIDEO + WIDTH + HEIGHT +
  ROTATION metadata, rotation applied; null on anything unexpected.
- `probeDurationMs(resolver, uri): Long?`, `posterBitmap(resolver, uri,
  maxEdge): Bitmap?` — `getScaledFrameAtTime(0, OPTION_CLOSEST_SYNC, …)`.
  All never-throw.

### 3.5 Compositor integration (device-gated)

- `OverlaySlot` gains: `videoSt`, `videoSurface`, `videoPlayer`,
  `videoStMatrix[16]`, `videoUvBuffer` (FILL cover-crop UVs),
  `videoPendingFrame`, `videoState`, `isVideo`.
- New OES overlay program: OES sampler + `uSTMatrix` + `uAlpha`
  (passthrough program + alpha). Built lazily only when the first video
  slot builds; failure drops that video slot loudly, keeps static slots
  (video is best-effort runtime media; static is deterministic config).
- Video slot build (GL thread): probe → FIT/FILL measure (shared math) →
  resolve (same stack) → style-only 2D texture (underlay over
  transparent content) + OES texture + ST + player prepare.
  Over-budget/invalid/unprobable → slot skipped loudly (same as images).
- Frame path: for video slots with a pending frame —
  `updateTexImage()` (catch → FAILED), `getTransformMatrix()`, draw
  style quad then OES quad at the content inset with slot UVs. No
  pending frame / not prepared → draw style only (video fades in on
  first frame; no poster texture in-stream — honest motion-or-nothing).
- Static screen freeze: video ST `onFrameAvailable` posts a coalesced
  on-demand redraw (re-run passthrough + slots + swap, reusing the last
  input timestamp — arrival-order fallback, documented), guarded by
  `glReady` + first-input-latched. Serialized on the GL queue; one
  redraw per video frame max.
- `setVideosPaused(paused)`: async post to GL (best-effort fast path) +
  volatile `videosPaused` flag for the service badge. Revoke callback
  calls it (runs on the GL handler already).
- Teardown order inside `releaseOverlaySlotsLocked`: 1) players
  stop+release (bounded rendezvous each), 2) ST listeners nulled + STs/
  Surfaces released, 3) textures deleted, 4) programs deleted. Shared
  by EGL teardown and H8 rebuild.

### 3.6 SceneSource + service wiring

- `SceneSource` passes `videoProbe` + `videoPlayerFactory(appContext)`
  into the compositor (mirrors `imageProvider`); revoke callback also
  calls `compositor.setVideosPaused(true)`; exposes `isVideoPaused`.
- No planner change: video scenes switch IN_PLACE like all scenes
  (players restart deterministically on rebuild).
- `publishState` gains `EXTRA_STATUS_VIDEO_PAUSED` (one line, no
  signature change).

### 3.7 Editor + preview (honest states)

- ADD VIDEO + `OpenDocument` (`video/*`) + persistable grant (mirrors
  image); replace/delete/reorder via existing layer ops; FIT/FILL
  reuses `ScaleModeRow`.
- Video card: poster thumbnail labeled **"Poster preview — motion plays
  in the live stream"** (MMR sampled ≤ 256), asset line
  `"<W> × <H> px • <M:SS> • <size>"` (each piece falls back honestly),
  state badge from real derived signals:
  - no URI → NOT LOADED; probe fail → FAILED + reason;
  - probe ok + scene not live → READY ("plays when the scene is live");
  - probe ok + live (broadcasting, enabled, in active scene) → PLAYING;
  - service `videoPaused` extra true → PAUSED.
- Preview canvas: poster drawn in the measured box (same FIT/FILL math),
  labeled as poster via the card note. No live decode in the editor.

## 4. Supported formats + limitations

- Formats: whatever the device's MediaPlayer demuxes/decodes from a
  content URI (typically MP4/M4V/3GP/WebM with H.264/H.265/VP8/VP9;
  device-dependent, never claimed beyond that).
- Limits: ≤ 2 concurrent decoding video layers per scene (extras show
  FAILED "decoder budget"); ≤ 4 total layers (existing H4 cap).
- Audio tracks decode but render silent (`setVolume(0,0)`); documented,
  not disabled at the track level (no public MediaPlayer API for that).
- `resume()` is implemented + state-tested but has no service trigger
  yet (revoke is terminal; no transport UI in scope) — reported
  precisely, not claimed as wired.
- Device-gated (like H2): EGL/MediaPlayer/ST cannot execute on JVM.
  Proven by Android compile + lint + CI packaging; S25 motion gate
  stays PENDING (after 7 PM UAE only).

## 5. Tests (all behavioral, no source-string logic tests)

New `SceneVideoPlaybackTest` (+ video cases reuse existing suites):

- VIDEO v5 round-trip; corrupt/blank/oversized video URI drops the
  layer (codec + `of()`); legacy v1–v4 unaffected (FIT/TEXT defaults).
- State machine: full transition matrix incl. invalid rejections,
  error stickiness, revoke→pause, stop terminal, rebuild reset.
- Budget: 0/1/2/3+ videos, disabled excluded, order-deterministic.
- Rotation swap incl. garbage degrees; FIT/FILL geometry on video dims.
- Planner NOOP/IN_PLACE/SWAP with VIDEO configs; portrait + landscape
  placement via the pure stack.
- No existing test weakened or deleted.

## 6. Build order

1. VIDEO kind + codec/of gates + budget const + pure playback file.
2. `SceneVideoPlaybackTest`; harness wire; 579 + N green.
3. `SceneVideoProbe` + `SceneVideoPlayer` (Android shells).
4. Compositor video slots + OES program + redraw + pause + teardown.
5. SceneSource + service extra wiring.
6. Editor + activity (picker, card, badge, preview poster).
7. Full gates → commit → push → CI (exact SHA) → artifact + signing.
