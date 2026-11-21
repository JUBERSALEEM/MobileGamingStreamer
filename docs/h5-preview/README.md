# H5 layout-proof renders (visual evidence — read this first)

These PNGs are **layout-proof renders, NOT app screenshots**. No device
or emulator was available; full Scene Editor UI screenshots remain
S25-gated (see `docs/H5_SCENE_EDITOR_DESIGN.md` §7).

What IS real in these images:

- Box positions, sizes, ordering, and alpha come from the actual
  production math: `SceneConfigCodec.defaultScene`,
  `SceneOverlaySpec.of`, `ScenePreviewBoxes.layout`, and
  `SceneOverlayStack.placeRect/resolve` — the same functions the
  editor preview and the GL renderer call.
- `h5-starter.png`: the real default seed (1 layer, top-left).
- `h5-multilayer.png`: four corner layers with z-order badges and a
  50%-opacity layer.
- `h5-visibility.png`: a disabled layer as a ghost outline (the
  renderer never streams disabled layers).

What is approximated:

- Glyph metrics use JVM fonts (AWT), not Android `Paint`, so text
  widths differ slightly from the on-device renderer. Positions,
  clamping, ordering, and visibility behavior are unaffected.
- The caption/footer bars and dark background are proof scaffolding,
  not app UI.

Regeneration: `/tmp/proof/Proof.kt` (build-sandbox scratch, not
shipped) compiled against `app/src/main/.../scene/*.kt` with the
project kotlinc and run headless. Re-run after any H4/H5 layout change
and re-commit the PNGs.
