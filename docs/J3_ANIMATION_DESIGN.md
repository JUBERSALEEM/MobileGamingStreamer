# J3 — Animation Engine

Status: IMPLEMENTED (21 new tests; suite 619/619 on both JVM harnesses; neg control green).

## 1. Investigation findings

- **Draw path** (`SceneCompositorGL`): each overlay slot draws a static NDC
  quad (`floatBufferOf(entry.ndcQuad)`) with a `uAlpha` uniform; video adds
  an OES quad at a content-inset NDC. All draws are GL-thread-only,
  serialized with rebuilds/teardown on the GL handler queue.
- **Tick precedent** (H9): a single delayed runnable, armed only while a
  dynamic slot exists, canceled on release. Zero overhead when idle.
- **Redraw precedent** (J2): coalesced on-demand re-presentation
  (`requestVideoRedrawLocked`) keeps motion alive when the screen VD is
  static. Animation reuses the same path.
- **NDC mapping** (`SceneTextLayerLayout.ndcQuad`): pure, pixel-Y-down to
  NDC-Y-up, triangle-strip order. Rotation must happen in PIXEL space
  (rotating NDC distorts under non-uniform aspect), then map to NDC.
- **Preview** (`ScenePreviewCanvas`): per-box absolute draws inside a
  Compose `Canvas`; `H9` already recomposes on a 1 s tick. DrawScope
  `translate`/`rotate`/`scale` scopes can wrap each box for motion.
- **Codec**: v5, 18 fields. Animation config is new persisted state, so a
  19th field + **v6 bump is unavoidable** (unlike J2's kind-only change).
  v1–v5 decode with animation=null; corrupt v6 anim tokens drop the layer
  (same policy as kind/scale).

## 2. Architecture

### 2.1 Pure core (JVM-tested) — `SceneAnimation.kt`

- `SceneAnimPreset`: NONE + 19 timed presets — FADE_IN/FADE_OUT,
  SLIDE_IN_*/SLIDE_OUT_* (LEFT/RIGHT/TOP/BOTTOM), SCALE_IN/SCALE_OUT,
  PULSE, ZOOM, DRIFT_LEFT/RIGHT/UP/DOWN, SPIN. All are TIMED transforms
  (phase 0→1 over duration+delay), not removal-triggered exits: an OUT
  preset starts placed and leaves; with LOOP it repeats.
- `SceneAnimRepeat { ONCE, LOOP }`, `SceneAnimEasing { LINEAR, EASE_IN,
  EASE_OUT, EASE_IN_OUT }` (quadratic easings, pure, boundary-tested).
- `SceneLayerAnimation(preset, durationMs, delayMs, repeat, easing)` with
  validating `of()` (duration 100–10000, delay 0–10000; garbage → null)
  and codec `encode/parse` (`"PRESET,duration,delay,REPEAT,EASING"`).
- Clock: `phaseAt(anim, elapsedMs): AnimPhase?(phase01, active)` — null
  only for NONE; `active=false` at rest (before delay? NO — before
  delay the slot shows the start state and time still runs, so active
  until ONCE completes; LOOP is always active).
- Geometry: `transformFor(preset, easedPhase, rectPx, canvasW, canvasH):
  SceneAnimTransform(dxPx, dyPx, scale, alphaMul, rotationDeg)` then
  `writeTransformedNdc(rectPx, t, canvasW, canvasH, out8)` — pixel-space
  translate/scale-about-center/rotate-about-center, then the exact
  `ndcQuad` mapping. Identity transform reproduces `ndcQuad` bit-for-bit
  (pinned by test) — renderer/preview math parity by construction.

### 2.2 GL integration (device-gated)

- `OverlaySlot` gains: `hasAnimation`, `animRectPx` (style rect),
  `animVideoRectPx` (video content rect), `animScratchNdc` (8-float
  buffer, allocated once at build iff animated), `animScratchArr`
  (reused float[8]), `animScratchAlpha`, `animStartElapsedMs` (static
  slots share the build instant; video slots start at assignment, when
  the layer appears). Canvas dims come from the layout.
- Draw (`drawOverlaySlotLocked` + video OES quad): if animated, sample
  `phaseAt` at `elapsedRealtime - animStart`; non-identity transform →
  `writeTransformedNdc` into the scratch buffer and draw with it;
  `alphaMul` multiplies the slot alpha uniform. Identity/resting →
  base buffer, zero extra cost. One small sample object per animated
  slot per frame (necessary — time varies); zero bitmap/CPU-copy work.
- Driver: `animTickRunnable` @ 60 Hz (`postDelayed(16)`), armed only
  while some slot samples `active`; each tick calls the redraw request.
  `requestVideoRedrawLocked` is generalized to
  `requestMotionRedrawLocked` (video + animation share it).
- Lifecycle: rebuild = deterministic restart (new slots, new start
  times); release cancels the ticker (same lines as the H9 ticker);
  stop/revoke/destroy need no anim-specific work (no decoder/IO/thread
  owned — buffers are GC). Invalid specs never reach GL (codec drops
  them); `evaluate` defensively completes on garbage time.

### 2.3 Editor + preview (honest tiers)

- LayerRow animation section (all kinds): preset picker (a wrapping
  `FlowRow` of clay buttons — 20 options never fit the single-row
  `ClayOptionGroup`), duration slider (0.1–10 s), delay slider
  (0–10 s), repeat selector (ONCE/LOOP), easing picker. One callback
  `onLayerAnimation(sceneId, layerId, SceneLayerAnimation?)`
  (NONE → null), threaded MainActivity → AppShell → LayerRow.
  Persisted with the scene (v6).
- Preview: 50 ms tick ONLY while an enabled animated layer exists
  (else fully static — no recompose churn); any scene edit replays
  all animations from one start instant. Each box samples the SAME
  pure `phaseAt`/`transformFor` math (GL transform order:
  scale→rotate→translate about center); translate/scale fold into the
  box rect, rotation via canvas save/rotate/restore (editor badges
  track the rect but never rotate), alpha multiplied.
- Labels: static scenes keep the existing note; animated scenes show
  "animated preview: same timing engine as the stream". The
  PREVIEW — NOT LIVE badge still rules out the stream. Parity claim
  is math-only (tested pure core, same call semantics), never pixel
  parity; preview samples at ~20 fps vs GL ~60 Hz, same clock.

## 3. Preset semantics (pinned by tests)

- FADE_IN: alpha 0→1. FADE_OUT: 1→0, rests hidden.
- SLIDE_IN_d: from fully off-canvas (direction d) to placed.
  SLIDE_OUT_d: placed to fully off-canvas, rests out.
- SCALE_IN: 0→1 + fade 0→1 (about rect center). SCALE_OUT: 1→0 + fade.
- PULSE: scale 1→1.08→1 (sine, seamless under LOOP).
- ZOOM: scale 1→1.15 (sawtooth under LOOP — documented).
- DRIFT_d: 0→canvasMin/12 px along d (sawtooth under LOOP).
- SPIN: rotation 0→360° about center (seamless under LOOP).
- Slide distances derive from rect+canvas (fully off-screen both
  orientations); drift distance is deterministic from canvas dims.

## 4. Codec v6

- `VERSION="v6"`, field 19 = anim (`""` or the 5-token encoding).
  v1–v5 → animation null (pixel-identical restores). Corrupt token →
  layer drops. Encode always writes v6. Old clients drop v6 scenes
  wholesale (unknown version) — unavoidable for a new persisted field,
  documented here (cf. J2 doc §3.1 for why VIDEO avoided this).
- Test pins: 6× `startsWith("v6\n")`; garbage `"v6\nscene:a\tb"` →
  `"v7…"`, plus `"v6"`, `"v6\nbogus"` lines. Existing v5 decode tests
  stay as back-compat coverage.

## 5. Limits (honest, not implemented)

- No removal-triggered exits (rebuild drops slots immediately; OUT
  presets are timed, not exit hooks).
- No per-keyframe curves, no stagger/groups, no motion paths.
- Preview is ~20 fps recompose approximation; live GL is the reference.
- `resume()`-style transport doesn't exist for animations (restart on
  rebuild only) — consistent with the no-transport scope.

## 6. Build order

1. Pure `SceneAnimation.kt` (model + clock + geometry + codec glue).
2. Model/codec v6 (`StreamSceneLayer`, `SceneOverlaySpec.of`, codec).
3. `SceneAnimationTest` + pin updates; harness; 598 + N green.
4. GL slots + tick + draw sampling + redraw generalization.
5. Editor section + callback threading + animated preview + labels.
6. Phase K roadmap doc (record ONLY — §6 of the assignment).
7. Gates → commit → push → CI (exact SHA) → artifact + signing.
