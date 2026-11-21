# H6 — Image & Graphic Layers

Status: IMPLEMENTED (500 JVM tests green, CI on exact HEAD).

Implementation notes (review amendments, all within scope):
- `placeRect` keeps H3 delegation for text top-left only; image
  top-left uses the arithmetically identical generic branch (pinned).
- Unloadable images skip loudly at build; over-cap/corrupt stacks
  still fail loudly into the ScreenSource fallback via `resolve`.
- Display size is absolute pixels (documented in §2); preview and
  renderer share placement math exactly, pixel fidelity best-effort.

## 0. Goals / non-goals

- Add PNG/logo image layers to the H2–H5 scene stack: create, edit,
  delete, reorder, anchor, margin, max-width, opacity, visibility.
- Reuse the existing composition rules end to end: H4 `resolve` /
  `placeRect` place image boxes; the GL overlay slots draw them; the
  editor previews them with the same math.
- Non-goals: camera/animated/SVG sources, network URLs, filters,
  per-layer rotation, format conversion UI.

## 1. Architecture

- New pure `SceneLayerKind { TEXT, IMAGE }`. `StreamSceneLayer` gains
  `kind` (default TEXT) and `imageUri: String?` (default null);
  `SceneOverlaySpec` gains the same two fields through kind-aware
  `of()` validation. Text behavior is byte-identical: TEXT keeps the
  exact H4/H5 rules (non-blank text ≤ 64 chars).
- IMAGE validation (pure, tested): `imageUri` non-blank, length ≤
  `MAX_IMAGE_URI_CHARS` (2048), scheme allowlist
  (`content://`, `file://`, `android.resource://`, case-insensitive).
  Text is ignored for images (stored empty). Loadability is NOT a
  pure property: a valid spec whose bytes are missing/unreadable is
  dropped loudly at build time, never a stream failure.
- Codec v2: header `v2`, layer lines carry 10 fields (existing 8 +
  kind + imageUri). `decodeScene` accepts v1 (8 fields → TEXT with
  defaults) and v2; unknown kinds and bad URIs drop the layer.
  `encodeStore` always writes v2. H5 stores decode unchanged
  (backward compatible); old app versions cannot read v2 (same-app
  upgrade path, documented).
- Image layers count toward the existing 4-layer cap and 16-scene
  cap: no extension. Prefs stay bounded (URIs ≈ 100 chars, capped).

## 2. Measurement (pure)

- New pure `SceneImageLayer.measuredSize(bitmapW, bitmapH,
  maxWidthPx, maxHeightPx): SceneSizePx?`: fit-inside aspect scaling
  with Long integer math (no overflow, no divide-by-zero), null on
  invalid input. Display size is absolute pixels (documented);
  placement rules are canvas-relative as for text.
- `placeRect` delegates TOP_LEFT to H3 only for TEXT specs; IMAGE
  TOP_LEFT uses the generic branch, which is arithmetically identical
  to H3 layout for top-left (left/top at margin, same capping). A JVM
  test pins generic-branch TOP_LEFT == H3 layout so the H4
  "identical output" property cannot silently break. No text
  behavior changes.

## 3. Loading (Android, never throws)

- New `SceneImageLoader` (ContentResolver-based, all entry points
  catch Throwable → null):
  - `decodeBounds`: cheap dimensions for aspect math (no bitmap).
  - `decodeBitmap(uri, maxDimPx)`: two-pass bounds + `inSampleSize`
    downsample, capped at `MAX_IMAGE_BITMAP_PX` (512px), RGB_8888.
  - `loadThumbnailBitmap`: API-29 `loadThumbnail` wrapper for editor
    UI (minSdk 29, no new dependency).
- Picker: `ACTION_OPEN_DOCUMENT` (`image/*`) via ActivityResult in
  MainActivity; `takePersistableUriPermission` (read) so access
  survives restart; failure → user-facing `sceneError`, no layer.
- Missing/revoked/corrupt bytes → layer skipped with a loud
  sanitized log (scheme + lengths only, never URI content or bytes).

## 4. Renderer (SceneSource + GL)

- `SceneSource.start` builds `imageProvider: (String) -> Bitmap?`
  over the application context (never throws; null on any failure)
  and passes it to `SceneCompositorGL`. GL stays Context-free.
- Overlay build (once per start, on the GL thread, never per frame):
  TEXT layers measure/render exactly as H4/H5; IMAGE layers decode
  once via the provider → aspect-fit measure → scale to the resolved
  rect → shared texture-upload path. Provider null or unplaceable →
  skip loudly, keep remaining overlays (an image must never kill a
  stream or its sibling text layers). Over-cap/corrupt lists still
  fail loudly into the existing start-failure → ScreenSource
  fallback path via `resolve`.
- No per-frame decode: textures upload once and are reused by the
  existing slot draw loop (`drawOverlaySlotLocked` unchanged).
- Cleanup: image bitmaps ride the existing slot lifecycle (upload →
  `slot.bitmap` → recycled in `releaseEglLocked`); decoded
  pre-scale originals are recycled immediately after scaling;
  try/finally recycles decoded-but-unclaimed bitmaps if the build
  throws. Ownership: GL owns every bitmap the provider returns.

## 5. Editor + preview

- SCENES page: ADD TEXT (existing) + ADD IMAGE (gallery picker).
  Image rows show a thumbnail (or an "unavailable" placeholder),
  uri-derived caption, and the full shared control set
  (anchor/margin/max-width/opacity/visibility/reorder/delete);
  the text field is replaced by a REPLACE action (re-pick).
- Preview canvas draws image boxes with `ScenePreviewBoxes` (same
  `resolve`), filling boxes with thumbnail pixels via `drawImage`;
  unloadable images render as labeled placeholders and are counted
  in the warning line. Layout parity is exact (shared math); pixel
  fidelity is best-effort and documented as such.

## 6. Compatibility

- H2–H5: legacy two-string path untouched (`sceneLayers == null`);
  v1 stores decode to identical scenes; text specs validate
  identically; service selection/fallback/Stop/revocation untouched
  (no service change required: the config string already flows).
- H5 audit fixes preserved (margin coerce, pure store resolution).

## 7. Tests

- Codec: v2 round-trip (text + image), v1 backward-compat decode,
  bad-kind/bad-scheme/oversized-uri drops, image streamability gate.
- `SceneImageLayerTest`: aspect-fit math (wide/tall/square,
  upsample refusal, invalid inputs, overflow-safe dimensions).
- `SceneOverlayStackTest`: generic TOP_LEFT == H3 layout pin.
- Structure pins: GL build calls the provider (once-per-layer at
  build, never in the draw loop — negative pin on
  `drawOverlaySlotLocked`), SceneSource wires a non-throwing
  provider, loader catches Throwable.
- Full gates: 482+ JVM suite, K222 scene compile, friend-paths,
  readiness audit, CI (tests + lint + assemble + sign) on exact HEAD.

## 8. Build order

1. `scene/SceneConfig.kt`: kind enum, fields, kind-aware `of()`,
   codec v2 + v1 decode.
2. `scene/SceneImageLayer.kt` (NEW, pure) + `placeRect` kind branch.
3. `scene/SceneImageLoader.kt` (NEW, Android).
4. `scene/SceneSource.kt`: provider wiring.
5. `scene/SceneCompositorGL.kt`: image build path + cleanup.
6. `SceneEditorPage.kt` + `MainActivity.kt`: picker + image rows.
7. Preview image drawing.
8. Tests + harness + gates + commit + push + CI.
