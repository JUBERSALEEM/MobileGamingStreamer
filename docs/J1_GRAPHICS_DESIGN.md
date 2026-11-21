# J1 — Advanced Graphics & Asset Layers

Status: SPECIFICATION (flip to IMPLEMENTED only when the subphase commits green).

## 0. Baseline findings (inspected, not assumed)

- H6 image layers: URI-gated, decode-once (512px cap, ARGB_8888,
  alpha preserved — transparent PNG logos already work), loud
  skip on missing bytes, "Image unavailable" + REPLACE in the
  editor, aspect-fit only.
- Persistable URI grants are taken at pick time
  (`takePersistableUriPermission`); every load path re-opens the
  stream, so a revoked/deleted asset degrades to
  unavailable-never-stale. No re-verification or asset-state
  surfacing exists beyond the thumbnail placeholder.
- Supported formats = platform `BitmapFactory` set (PNG/JPEG/
  WebP/GIF-first-frame/BMP). No new dependency is justified:
  SVG/raw would need a renderer for zero evidenced demand.
- Missing (genuine gaps): per-layer scale modes (fit vs fill),
  asset health/dimension surfacing, pinned memory-limit guards.

## 1. Scale modes (core feature)

- New public `SceneScaleMode { FIT, FILL }`, per-layer field on
  `StreamSceneLayer` + `SceneOverlaySpec` (default FIT — H6
  behavior byte-identical).
- FIT: existing `measuredSize` aspect-fit (unchanged).
- FILL: new pure `fillSize` (frame = min(maxWidth,roomW) ×
  min(maxHeight,roomH) — spans full available height at the
  layer's max width) + new pure `fillCrop` (centered
  cover-crop in bitmap space, floored bias matching the repo
  leftover-pixel convention). Both Long-safe, null-on-invalid.
- GL: FILL draws src-crop → dst-content directly (no scaler
  temp — fewer copies than FIT); FIT path untouched. Preview:
  same box math, `drawImage` with source rect (crop phase may
  differ ~1px from GL due to thumbnail-vs-decode dims —
  documented best-effort pixel parity, exact layout parity).
- Canvas-relative throughout: portrait/landscape/orientation
  support falls out of the existing room math (tested both).

## 2. Asset states + URI lifetime

- Editor rows gain an asset line: dims + "Stored asset" when
  `decodeBounds` succeeds; "missing or revoked — REPLACE to
  relink" otherwise (remember-cached, same main-thread pattern
  as the existing thumbnail load).
- Lifetime audit (investigated): grants persist across restart;
  revocation/deletion/uninstall-of-provider surfaces as
  unavailable on next load (every path re-opens); REPLACE
  re-takes the grant. No code change — documented + badged.

## 3. Limits (verified + pinned, not new)

- 512px working copy, inSampleSize downsampling, try/finally
  recycle, OOM-caught loader, decode-once-per-build,
  texture-per-slot. J1 pins the loader guards structurally so a
  refactor cannot silently drop them.

## 4. Codec v5 + migration

- Layer lines 17→18 fields (+ scaleMode). v1–v4 decode with FIT
  default (existing scenes pixel-identical); corrupt scaleMode
  drops the layer (consistent with kind/anchor policy).
  Orientation header logic (`version<4` branches) already covers
  v5 — verified, plus migration tests.

## 5. Compatibility

- H6/H7/H8/H9/H10/Phase I untouched behaviorally: FIT default,
  same upload/draw/switch/tick/teardown paths, same caps. No
  service change. J2 reuses scaleMode for video.

## 6. Tests

- `SceneImageLayerTest`: fillSize (box/cap/invalid), fillCrop
  (wide/tall/square/invalid/centering), portrait-room FILL.
- Codec: v5 round-trip, v1–v4 ⇒ FIT, corrupt ⇒ drop, pins +
  garbage churn (v5 live, unknown ⇒ v6).
- Pins: GL + preview share `fillCrop`; loader keeps
  cap/downsample guards; editor Scale option threaded;
  thumbnail probes asset health.
- Full gates: 568+ JVM suite, K222, CI on exact HEAD.

## 7. Build order

1. ScaleMode enum + model + `of()` + pure math + codec v5.
2. GL FILL branch.
3. Preview FILL + asset line + Scale UI + threading.
4. Tests + pins + gates + commit + push + CI.
