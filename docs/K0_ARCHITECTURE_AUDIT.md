# K0 — Custom Scene Studio: architecture audit

Status: AUDIT (planning only — no application code changed in K0).

Baseline: branch `arena/239daa25-mobilegamingstreamer`, HEAD
`d893a0b063e661f08d11c7fdde9569b75adb0c76` (Phase J complete, J5
green: 669/669 both harnesses, CI SUCCESS, PR #2 OPEN/MERGEABLE/
CLEAN). J1–J5 design docs all read IMPLEMENTED; implementation
verified present (`SceneTransition`, `SceneMotionPolicy`,
`SceneHardeningTest`, J5 GL fixes).

## 1. Current architecture map

```
Editor (Compose)            Pure cores (JVM-tested)              GL / Android shells
─────────────────           ──────────────────────              ──────────────────
MainActivity (state +       StreamScene/StreamSceneLayer        SceneCompositorGL
 actions, push-live)          (scene/SceneConfig.kt)              (EGL, slots, ticks)
SceneEditorPage (cards,       SceneOverlaySpec +                  SceneSource (VD,
 sliders, preview Canvas)     SceneOverlayStack.resolve           projection, teardown)
AppShell (Studio pages)       placeRect / ndcQuad                 SceneVideoPlayer/Probe
ScenePrefsStore (prefs)       SceneConfigCodec v6 (encode/         SceneImageLoader
TransitionCard / anim         decode/store/migrate)               RtmpBroadcastService
 preview / transition demo    ScenePreviewBoxes (preview math)    (planner-driven switch)
                              SceneCanvasProfile (1080p ref)      FloatingControlService
                              SceneAnimation/Transition/Motion     (out of scene model)
                              SceneVideoBudget/Playback
                              SceneSwitchPlanner, Controller
```

Data flow (stream): prefs → `MainActivity.scenes` → intent extra
→ `RtmpBroadcastService.updateScene` → planner → IN_PLACE
(`SceneSource.updateScene` → GL rebuild/transition) or library
`changeVideoSource`. Data flow (preview): `scenes` → Paint
measure → `ScenePreviewBoxes.layout` (same `resolve`) → editor
`Canvas` + z-badges + anim tick. The two renderers share all
pure math; only the shells differ.

## 2. Existing vs missing matrix

| Capability | State | Where |
|---|---|---|
| Multiple named scenes + CRUD + switch | EXISTS | MainActivity, ScenePrefsStore, codec store (cap 16) |
| Scene collections | MISSING | store has flat list only |
| Default/active scene | EXISTS (active id; default = first/seed) | `resolveStoredScenes` |
| Text/image/video/clock/timer layers | EXISTS | kinds + builders + GL slots |
| Reusable sources (place same asset twice) | MISSING | uri embedded per layer, no identity |
| Add/remove/reorder/visibility/animation/style | EXISTS | LayerRow + actions |
| Duplicate layer / lock / front-back jump / groups | MISSING | only stepwise move, no lock |
| Tap-select / drag / handles / rotation / zoom-pan | MISSING | cards + static preview only |
| Free x/y position, explicit size, rotation | MISSING | anchor+margin+maxWidth model |
| Numeric inspector (pos/size/rot) | MISSING | sliders for margin/width/alpha |
| Tap-to-edit text | PARTIAL | text field in card, not on canvas |
| Portrait + landscape canvases | PARTIAL | cosmetic label + 1080p refs; one layer list |
| Templates/themes | MISSING | `defaultScene()` only |
| Safe live switch (no RTMP restart) | EXISTS | H8/J4/J5 + isolation pins |
| Live push of EDITS | MISSING | edits go live only on re-switch (USE) |
| Chat widgets | MISSING (future) | floating panel only, not a layer kind |
| Persistence + self-heal + v1–v6 compat | EXISTS | prefs + `resolveStoredScenes` |
| Import/export | MISSING | no format, no UI |
| Preview↔live parity | PARTIAL | pixel-exact at 1080p only (absolute margins) |

## 3. Data-model analysis

`StreamScene(id, name, layers, orientation)` → `StreamSceneLayer`
(19 fields: identity, content, anchor, z, margin, maxWidth, alpha,
enabled, kind, imageUri, 7 style, scaleMode, animation) →
`toOverlaySpec()` validation gate → `SceneOverlaySpec` →
`SceneOverlayStack.resolve` → draw entries. Strengths: single
validation funnel, total ordering (`normalizedZ`), codec v1–v6
read compat with self-healing store. Gaps for K: no source
identity (K2), no free transform (K3/K4), no per-orientation
placement (K6), no collection entity (K1), no template entity
(K7). All gaps are EXTENSIONS (new optional fields/entities +
codec v7 that still reads v1–v6), never rewrites.

## 4. Coordinate and transform analysis

Placement today: `placeRect` maps (anchor ∈ 5, marginPx,
maxWidthPx, measuredPx) → clamped `SceneRectPx` on the canvas;
NDC via `ndcQuad`; J3 `writeTransformedNdc` adds (dx, dy, scale,
alpha, rotationDeg) for animation. The GL renderer can ALREADY
draw translated/scaled/rotated quads — only the MODEL lacks a
base transform. Recommendation (K3/K4 to finalize): optional
free transform per layer = center (fx, fy as canvas fractions) +
size scale multiplier on measured content + rotation degrees.
Fractions (not px) scale across 720p/1080p/1440p outputs and fix
the absolute-margin parity gap. `placeRect` takes the free path
when present, the anchor path otherwise (v1–v6 pixel-identical).

## 5. Preview vs live parity analysis

Parity mechanism: both sides call `SceneOverlayStack.resolve`
+ `ndcQuad` + `SceneAnimation` math; preview on the 1080p
reference canvas (`SceneCanvasProfile`), live on output dims.
GAP: at non-1080p encoder profiles, absolute margins/widths
place differently than the 1080p preview (honestly scoped in
KDoc, plus a portrait/landscape mismatch hint in the editor).
K10 direction: preview resolves on the LIVE output dims (from the
active encoder profile) when known, reference canvas otherwise;
free-transform fractions resolve identically at any size. Editor
chrome (z-badges today, selection handles in K) lives in the
editor `Canvas` only — stream exclusion by construction, same
guarantee as today. No preview artwork may be hardcoded (K7).

## 6. Persistence and codec compatibility analysis

Store: `mgs_scenes` prefs, `scenes_v1` (blocks ≤ 16) +
`active_scene_id` + `scene_transition_v1`; every load/save
funnels through pure `resolveStoredScenes`/`resolveSceneSave`
(self-heal + reseed + rewrite-if-different). Codec v6 writes,
reads v1–v6; corrupt lines drop, scenes survive. K direction:
codec v7 adds free-transform field(s) (+ source refs in K2,
orientation placements in K6); store gains collection key(s) in
K1 with the same resolve/rewrite discipline; import/export (K10)
reuses the store encoding over share-sheet/ SAF text (no new
format). Migration rule (established): old data always decodes
to behavior-identical scenes; new fields default to today's
rendering.

## 7. Portrait/landscape compatibility analysis

Today: `orientation` is a per-scene label (v4 header) selecting
the 1080p reference canvas + preview aspect; ONE layer list;
cross-orientation streaming caps to fit (with an honest hint).
K6 direction: shared layer identity/content + PER-ORIENTATION
placement (each orientation keeps its own transform set;
switching never destroys the other). Sources (K2) shared across
orientations. Output dims come from the encoder profile, not the
scene — the renderer already resolves any spec list on any
canvas, so no GL change is needed beyond the new spec fields.

## 8. Live-editing and scene-switching risk analysis

Proven safe today: IN_PLACE rebuild / CUT / blends never touch
RTMP/encoder/audio/projection (J5 structural pin); swaps use the
retaining helper; invalid scenes NOOP (planner + `streamableSpecs`
gate); `editingSceneId` ≠ `activeSceneId` already allows editing
a non-live scene. KEY FINDING: edits push live ONLY on scene
re-switch (single `requestSceneUpdate` call site, `onActiveScene`)
— today's default is "edits never disturb the live render".
K8 decision (explicit, no silent change): define push-live as
debounced auto-push of ACTIVE-scene edits via IN_PLACE, or
explicit Apply — either way through the existing isolated path,
with invalid compositions keeping last-good. Touch handling (K3)
must be scoped to the canvas `pointerInput` so editor navigation
never breaks. No streaming-pipeline, audio, RTMP, or
MediaProjection changes in any subphase.

## 9. Touch gesture and Compose integration design

No new dependencies: Compose BOM 2025.10.01 (already used)
provides `pointerInput`, `detectTapGestures`,
`detectDragGestures`, `detectTransformGestures`; `graphicsLayer`
already in use. Design: selection state hoisted in MainActivity
(next to `editingSceneId`); `ScenePreviewCanvas` gains a gesture
layer mapping touch → reference-canvas coords (inverse of the
existing box normalization) with hit-testing topmost-first over
`ScenePreviewBox`es; drag writes free-transform center (K3),
handles write size/rotation (K4); handles/guides/selection box
drawn in the editor `Canvas` above boxes (never in GL output);
zoom/pan as a canvas-local transform with reset (K3); canvas
`pointerInput` consumes only canvas gestures (K3 acceptance:
scroll/nav unaffected). Multi-touch via `detectTransformGestures`
(2-finger pan/zoom while a layer is selected).

## 10. Proposed K1–K10 dependency graph

```
K1 collections/store ─┬─▶ K2 sources ─┬─▶ K3 transform model + tap/drag
                      │               ├─▶ K5 layer org ─▶ K4 handles/inspector
                      │               ├─▶ K6 orientations (needs K3 model)
                      │               ├─▶ K7 templates
                      │               └─▶ K9 widgets-in-model
                      └─▶ K8 safe live editing (needs K1; parallel-safe)
K10 persistence/parity/regression — last (needs all; each phase ships own tests)
```

RECOMMENDED CHANGE vs the proposal: fold the free-transform
MODEL (layer/spec/resolve/codec-v7 + renderer + preview parity,
fully tested) into K3 ahead of gestures — drag needs a position
to write to — and keep K4 for handles/rotation/inspector UI. K5
moves after K2 (duplicate/lock/visibility extend the layer
model). K8 can start once K1 lands (switching exists; push-live
semantics are additive). K10 closes with parity + import/export +
full regression. No camera capture, browser sources, or chat
data-source work anywhere (explicitly out of scope).

## 11. Files likely to change per subphase

- K1: `scene/SceneConfig.kt` (collection entity + store v2),
  `ScenePrefsStore.kt` (keys), `MainActivity.kt` (actions),
  `AppShell.kt` + `SceneEditorPage.kt` (collection UI), store
  tests. No GL/service changes.
- K2: `SceneConfig.kt` (source registry + layer `sourceId`),
  codec v7 (split embedded uris on migrate), editor source
  picker, loader cache-by-source (optional), tests.
- K3: `SceneConfig.kt` + `SceneOverlayStack.kt` (free transform
  + resolve path), `SceneCompositorGL.kt` (spec fields only, no
  pipeline change), `ScenePreviewBoxes.kt` + `SceneEditorPage.kt`
  (gesture canvas + hit-test + zoom/pan), `MainActivity.kt`
  (selection state), parity tests.
- K4: `SceneEditorPage.kt` (handles overlay + inspector),
  `SceneConfig.kt` (rotation/size validation), `ScenePreviewBoxes`
  (rotated-box hit math), tests. GL already rotates quads.
- K5: `MainActivity.kt` (duplicate/lock/front-back), `SceneEditorPage`
  (layer list UI), `SceneConfig.kt` (lock flag; groups only if the
  model supports without renderer surgery), tests.
- K6: `SceneConfig.kt` (per-orientation placements + migrate),
  editor orientation switcher, preview both refs, parity tests
  both orientations. No GL pipeline change.
- K7: `SceneConfig.kt` (template instantiate + fresh ids),
  `ScenePrefsStore.kt` (template key), editor template UI, tests.
- K8: `MainActivity.kt` (push-live policy), service path UNCHANGED
  (reuse `requestSceneUpdate`), isolation regression pins, tests.
- K9: `SceneConfig.kt` (widget kinds in source model — CLOCK/TIMER
  exist; chat explicitly future), refresh-budget pins, tests.
- K10: prefs/codec (import/export), preview-on-output-dims,
  full parity + lifecycle + performance regression suites.

Explicitly UNCHANGED in all of Phase K: capture/encoder/RTMP/
audio/MediaProjection code, GL frame path and tick architecture,
J5 budgets and isolation guarantees (extended by pins, never
relaxed), existing tests (extended, never weakened).

## 12. Test strategy and acceptance criteria per subphase

Strategy (established J-pattern, kept): pure decision cores
behind Android shells; behavioral JVM tests for all pure logic
(new codec paths, resolve paths, hit-test math, placement
migration); structural pins ONLY where Android shells cannot
execute on the JVM (editor-chrome exclusion, isolation,
no-new-transport-calls); no test weakening; both harnesses +
CI on every commit. Groups (K5) ship ONLY if implementable
without renderer surgery, else deferred with rationale — no
half-model.

- K1: collections CRUD + default/active semantics + migration
  from flat store, all behaviorally tested; flat-store data
  opens unchanged.
- K2: place one source in N scenes (single asset identity);
  edit source vs override-per-scene semantics pinned; no
  accidental cross-scene mutation (test-proven).
- K3: tap selects topmost (hit-test unit tests); drag moves
  (model tests); canvas zoom/pan; navigation unaffected
  (manual + structural); preview↔GL rect parity ≤ 1 px at
  1080p both orientations.
- K4: numeric inspector round-trips through the model;
  aspect behavior per kind; rotation renders identically in
  preview and GL (parity tests); immediate preview updates.
- K5: order/duplicate/delete/lock/visibility behave + persist;
  locked layers reject canvas edits (tested).
- K6: independent placements per orientation survive switches
  (round-trip tests); sources shared; both previews correct.
- K7: templates instantiate editable scenes from the real
  model (no hardcoded art — structural pin); user themes
  persist.
- K8: push-live policy explicit + tested; invalid edits keep
  last-good stream; isolation pins still green; controls never
  in output (structural pin).
- K9: widgets in source model; refresh causes no per-tick
  texture alloc (pin); budgets unchanged; chat marked future.
- K10: import/export round-trips; corrupt-data recovery;
  preview-on-output-dims parity all profiles; full suite
  green; perf/lifecycle regressions pinned.

Requirements NOT safely implementable as drawn: none found —
every K item maps to an extension except chat widgets (no chat
data source exists — must stay FUTURE) and layer groups (ship
only if model-clean). Camera capture and browser sources are
out of scope, not architecture-blocked.
