# H10 — Perf/Compat Hardening

Status: IMPLEMENTED (551 JVM tests green, CI on exact HEAD).

Implementation notes (review amendments, all within scope):
- The `moduleFile` test helper prefers `app/<name>` so the repo-root
  build file cannot shadow the module one under the local runner
  (Gradle's module-dir CWD keeps resolving the bare name).
- Fuzz seeds are fixed (`0x1010`, `987654321`): identical mutations
  on JVM and CI, so stress can never flake between runners.

## 0. Goals / non-goals

- Prove no-leak lifecycle end to end (audit + pins + one real fix),
  prove the frame path allocates nothing heavy, prove codec/stack
  robustness under seeded stress, and pin the compat floor — all
  runnable on JVM + CI (no device claims; S25 stays PENDING).
- Non-goals: new features, new kinds, behavior changes, benchmark
  numbers without hardware (no fake perf claims).

## 1. Leak audit (all paths walked; one real fix)

- Bitmap ownership: every GL bitmap dies exactly once — slot
  bitmaps in `releaseOverlaySlotsLocked`, decoded-but-unclaimed in
  the build `finally`, scaler temps + originals in
  `composeImageBitmapLocked`, H9 tick reuses its bitmap (no churn).
  Rebuild throws recycle partial slots via the rollback's release.
- Handler/runner: the H9 ticker is canceled on slot release,
  input release (queue drain), and thread quit; wedged drivers
  time out, never hang (existing rendezvous).
- FIX (real, rare-path): `failStart` released EGL but not the
  input endpoint — when the overlay build threw after input setup
  (the scene-failure case), the input SurfaceTexture leaked: the
  thread quit below, so a later `releaseInput` could no longer
  rendezvous to free it. Fix: extract `releaseInputLocked()` and
  run it before `releaseEglLocked()` in `failStart`. No VD can be
  live there (start throws before VD creation), so ordering is safe.
- Service/surface/projection: swap success leaves the old source
  to the library's release; swap failure releases only the unused
  replacement; the four display-null sites also null the retained
  source (pinned count == 4 since H8).
- Compose: thumbnail bitmaps are `remember(measured)` — never
  reloaded by the 1s preview tick (pinned exact key); paints are
  remembered; per-tick work is glyph strings + draw calls only.

## 2. Frame-path allocation (pinned, not profiled)

- `onFrameAvailable` / `drawFrameLocked` /
  `drawOverlaySlotLocked` create no Bitmap/Canvas/Paint/Rect/
  buffers and upload no textures. Accepted per-frame allocs (tiny,
  necessary): one `SceneFrameMetadata` + the controller handle.
  No fps/latency numbers are claimed without S25 hardware.

## 3. Seeded stress (deterministic; same seed JVM + CI)

- `SceneCodecStressTest` (new): 400 seeded mutations (insert /
  drop / flip / truncate / splice over framing + hex + unicode)
  across v1/v2/v3/widget bases — `decodeScene`, `decodeStore`,
  and `streamableSpecs` never throw, and decode is a fixed point
  (`decode(encode(decode(x))) == decode(x)`).
- Store resolution idempotence: a second pass over repaired
  output is clean (no rewrites) with identical scenes/active/id.
- Max-store bound: 16 scenes × 4 layers with max-length fields
  encodes < 256KB and round-trips exactly (prefs transaction
  headroom, pinned).
- Stack determinism: all anchors × all kinds at 8K resolve
  identically across repeats (ids + rects + alpha).
- Tick-delay sweep in `SceneClockWidgetsTest`: co-prime stepping
  across two minute-periods stays in `(0, period]` everywhere.

## 4. Compat pins

- `minSdk = 29` pinned from `app/build.gradle.kts` (the floor
  `java.time` + `loadThumbnail` rely on); the two `SDK_INT >= 33`
  guards (receiver flag, parcelable overload) pinned present.

## 5. Tests / gates

- New `SceneCodecStressTest` + widget sweep + 5 structure pins;
  full gates: 541+ JVM suite, K222, CI on exact HEAD.

## 6. Build order

1. `failStart` fix.
2. Stress tests + sweep + pins + harness.
3. Gates + commit + push + CI.
