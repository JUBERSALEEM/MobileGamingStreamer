# Phase I — Portrait & Landscape Scene Canvas

Status: IMPLEMENTED (568 JVM tests green, CI on exact HEAD).

Implementation notes (review amendments, all within scope):
- v4 tolerates a 2-field header (missing orientation ⇒ default):
  only structurally corrupt headers (bad prefix/field count,
  blank id, bad escapes) drop the scene.
- The subtitle string-concat places the orientation label with a
  trailing `if/else` expression (`"..." + if ... else ...`),
  which parses as string-plus-choice — pinned by compilation.
- No GL change was required (composition was already
  output-canvas-relative); Phase I proves portrait composition
  behaviorally instead of rewriting the renderer.

## 0. Baseline findings (inspected, not assumed)

- Preview is hardcoded landscape: `SCENE_PREVIEW_WIDTH_PX=1920`,
  `SCENE_PREVIEW_HEIGHT_PX=1080`, `aspectRatio(16/9)`.
- `StreamScene` has NO orientation field; codec v3 scene header is
  `scene:<id>\t<name>` (no orientation); `SceneOrientation`
  (PORTRAIT/LANDSCAPE/SQUARE) exists but only `SceneGeometry` uses it.
- The GL renderer is ALREADY output-relative: `buildSlotsLocked`
  composes on `layout.outputWidth/Height` (real encoder dims),
  text size/margins derive from output height, and `placeRect`
  caps to canvas room — so a portrait output already yields a
  portrait composition with no renderer rewrite.
- The service already streams portrait: `VideoProfileSelector`
  builds 1080x1920 (1080p) / 720x1280 / 1440x2560 outputs and
  `setStreamIsPortrait` configures the encoder renderer.
- MISSING: per-scene design orientation (model/codec/persistence/
  editor), a portrait preview, and any proof that composition is
  correct on portrait canvases.

## 1. Architecture: one profile, one resolve

- New pure `SceneCanvasProfile`: `referenceCanvas(orientation)`
  returns 1920x1080 (landscape) or 1080x1920 (portrait) — exactly
  the 1080p encoder dims each way, so at 1080p reference ==
  output and preview boxes == renderer rects pixel-for-pixel.
  `orientationOf(canvas)` maps real canvases back (null for
  invalid/square). No orientation math anywhere else.
- New public `SceneDesignOrientation { PORTRAIT, LANDSCAPE }`
  (SQUARE deliberately excluded: scenes are designed for one of
  the two stream shapes; square outputs still compose via the
  orientation-agnostic `resolve`).
- Transformation model: editor measures against the reference
  canvas and normalizes (0..1); the renderer resolves the same
  specs on the output canvas. Same `resolve` rules + same aspect
  ⇒ same relative layout; absolute px scale with output. No
  second layout engine: preview keeps calling
  `ScenePreviewBoxes.layout` → `SceneOverlayStack.resolve`.
- Layer geometry stays absolute px and is NEVER rescaled or
  rewritten on orientation change: `placeRect` caps to room, so a
  landscape-tuned layer on a portrait canvas is capped (visible,
  uncorrupted), never lost. Orientation edits touch only the
  orientation field (pinned by test).

## 2. Model + codec v4 + migration

- `StreamScene` gains `orientation` (default LANDSCAPE; all
  existing constructions keep compiling).
- Codec v4: header `scene:<id>\t<name>\t<ORIENTATION>`, layer
  lines unchanged (17 fields). `decodeScene` accepts v1–v4;
  v1–v3 headers (2 fields) ⇒ LANDSCAPE (existing scenes keep
  byte-identical behavior). v4 tolerates a missing orientation
  field (⇒ default); an unknown token repairs to LANDSCAPE
  (cosmetic-metadata repair, consistent with blank-name repair —
  never drops a scene over a label).
- `encodeStore` always writes v4. `decodedOrientation(encoded)`
  helper (null ⇒ default) for service logging.

## 3. Editor + preview

- SceneCard (editing): "Canvas" option group (Landscape/Portrait)
  above the preview; `onSceneOrientation` threads
  Activity→AppShell→SceneEditorPage→SceneCard and persists via
  the existing `updateScene` (copy ⇒ persist ⇒ H8 push on USE
  unchanged).
- Preview: reference canvas from `scene.orientation`, aspect
  16:9 ↔ 9:16, text/image/widget measurement against ref dims,
  same style/z/alpha/ghost drawing. Mismatch hint (muted, no
  drama): explicit stream orientation ≠ scene orientation ⇒
  "Scene is X but the stream is Y — layers are capped to fit."
  AUTO ⇒ no hint.
- Scene subtitle shows orientation ("3 layers • 2 visible •
  Portrait") so mode is visible without opening the editor.

## 4. Renderer integration (verify, don't rewrite)

- No GL change required: composition is output-canvas-relative
  today. Phase I proves it with portrait-canvas behavioral tests
  (all anchors × all kinds resolve in-bounds; preview/reference
  boxes == renderer rects on identical dims, both orientations).
- Stream orientation is fixed at `prepareVideo` (encoder
  reality): it cannot change mid-stream. Scene orientation is
  design metadata — H8 switches recompose on the SAME output
  canvas regardless of scene orientation (no restart, no RTMP
  interruption, H8/H10 guarantees preserved). Changing stream
  orientation still requires a fresh GO LIVE (existing, honest
  behavior — documented, not altered).
- Service logs scene orientation beside layer count at start
  (sanitized: enum + counts only).

## 5. Compatibility

- H1–H10 untouched behaviorally: v1–v3 decode identically,
  defaults reproduce current pixels, legacy ADB path unchanged,
  fallback/stop/revocation/teardown unchanged. Duplicate scene
  preserves orientation (data-class copy); new scenes default
  landscape.

## 6. Tests

- `SceneCanvasProfileTest` (new, pure): reference dims (exact
  1920x1080 / 1080x1920), aspect preservation, `orientationOf`
  incl. square/invalid/extreme, portrait boundary placement for
  every anchor × every kind, preview/renderer parity both
  orientations (same dims ⇒ identical normalized boxes),
  8K-canvas determinism.
- Codec: v4 round-trip (portrait+landscape), v1/v2/v3 ⇒ default,
  corrupt token ⇒ default, missing field tolerated, garbage
  churn (v4→live, unknown ⇒ v5), `decodedOrientation`.
- Model: repeated orientation toggles preserve layers/geometry;
  portrait scene is streamable (`activeSceneEncoding` non-null);
  planner matrix works on v4 portrait encodings.
- Pins (UI-only, no pure logic exists): selector + 9:16 aspect +
  profile use in editor, `onSceneOrientation` in activity,
  service orientation log.
- Full gates: 551+ JVM suite, K222, CI on exact HEAD. S25:
  portrait stream composition, preview-vs-live likeness (PENDING).

## 7. Build order

1. `SceneCanvasProfile` + orientation enum + model + codec v4.
2. Pure + codec + model tests.
3. Preview orientation-awareness + mismatch hint.
4. Selector UI + threading + activity action.
5. Service log.
6. Pins + harness + gates + commit + push + CI.
