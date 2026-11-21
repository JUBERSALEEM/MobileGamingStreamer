# H7 — Advanced Styling

Status: IMPLEMENTED (514 JVM tests green, CI on exact HEAD).

Implementation notes (review amendments, all within scope):
- UI sliders offer the useful subset (radius ≤ 64, shadow ≤ 32)
  while the codec tolerates wider foreign values (≤ 256 / ≤ 64),
  rendered clamped — no offered value is unsupported.
- Shadows are solid offset rects (no blur): honest on all drivers,
  zero per-frame cost, composed once into the slot bitmap.
- Corrupt style fields drop the layer, consistent with the existing
  geometry policy; v1/v2 layers take pixel-identical defaults.

## 0. Goals / non-goals

- Per-layer styling for text AND image layers: text color, background
  fill, border (width + color), corner radius, offset shadow.
- One shared composition contract: renderer and preview compute
  padding from the same pure function, draw in the same order, and
  place through the same H4 `resolve`.
- Non-goals: blur shadows (expensive, dishonest on low-end drivers),
  gradients, custom color pickers (preset swatches only), rotation,
  per-layer fonts, animation.

## 1. Style model (pure)

- New style fields on `StreamSceneLayer` / `SceneOverlaySpec`
  (defaults = H6 appearance, so old scenes render identically):
  `textColor: Int` (default white), `backgroundColor: Int?` (null =
  none), `borderWidthPx: Int` (0 = none, ≤ 64),
  `borderColor: Int` (default white), `cornerRadiusPx: Int`
  (0 = square, ≤ 256), `shadowOffsetPx: Int` (0 = none, ≤ 64),
  `shadowColor: Int` (default translucent black).
- Validation in kind-aware `of()`: widths ≥ 0 and within caps;
  colors are any ARGB Int (no invalid value; unparseableCodec
  fields drop the layer, consistent with existing number policy).
- New pure `SceneLayerStyle`: `padding(spec)` (left/top = border,
  right/bottom = border + shadow) and `styledSize(content, spec)`
  (content + padding, null-safe). Both GL measurement and preview
  measurement call it — parity by construction.

## 2. Codec v3

- Layer lines carry 17 fields (v2's 10 + 7 style fields); colors as
  8-hex ARGB (`FFFFFFFF`), empty string for null background.
- Decode accepts v1 (→ text defaults), v2 (→ text/image + style
  defaults), v3; encode always writes v3. Corrupt style fields drop
  the layer (safe recovery, consistent policy). Old scenes decode
  to pixel-identical specs (defaults match H6 rendering).

## 3. Renderer composition (build-time only, zero per-frame cost)

- Measurement adds style padding (text bounds / image aspect-fit,
  then `styledSize`); `resolve` places the styled box unchanged.
- Slot bitmap draws back-to-front in one fixed order: offset shadow
  rounded-rect (if any), background rounded-rect (if any), border
  stroke (if width > 0), content (glyphs in `textColor` / scaled
  image) at the padding offset. Radius clamps to half the box.
- Single texture per layer as before; draw loop untouched; alpha
  still via the `uAlpha` uniform over the composed bitmap.

## 4. Preview composition (same rules)

- Preview measures through the same `styledSize`, resolves through
  the same `resolve`, and draws the same order (shadow / bg /
  border / content) with Compose Canvas primitives. Layout and
  order parity is exact; raster parity is best-effort across
  different rasterizers (documented, never claimed otherwise).

## 5. Editor controls (only shipped capabilities exposed)

- Text color swatches (text layers), background swatches incl.
  None, border width slider (0..64) + color swatches, corner radius
  slider (0..64), shadow offset slider (0..32) + shadow color
  swatches. Presets only — no free-form picker, so every offered
  value is valid by construction; Activity coerces widths into
  codec ranges. No control maps to unimplemented rendering.

## 6. Compatibility

- No service/SceneSource/GL-draw-loop/lifecycle changes: style
  flows inside the existing spec/config pipeline. H2–H6 behavior
  preserved (defaults reproduce H6 pixels; legacy paths untouched).
- H5 audit fixes preserved. Caps documented: style never extends
  the 4-layer / 16-scene limits.

## 7. Tests

- `SceneLayerStyleTest`: padding math, styled sizing, null-safety,
  cap edges.
- Codec: v3 round-trip (styled text + image), v1/v2 back-compat
  (→ defaults), corrupt-style drops.
- `of()` style validation (negative/over-cap → null).
- Structure pins: GL + preview share `SceneLayerStyle`; Activity
  coerces style widths; editor exposes no unimplemented control
  (pin the swatch/slider set against the spec fields).
- Full gates: 500+ JVM suite, K222, CI on exact HEAD.

## 8. Build order

1. `scene/SceneLayerStyle.kt` (NEW, pure) + spec/layer fields +
   `of()` validation.
2. Codec v3 + back-compat.
3. GL styled measure + slot composition.
4. Preview styled measure + draw.
5. Editor swatches/sliders + Activity/AppShell actions.
6. Tests + harness + gates + commit + push + CI.
