# H4 Design: Static Multi-Layer Overlay Stack (IMPLEMENTED)

Next engineering milestone after H2 (compositor infrastructure) and H3
(first static text overlay): generalize the single H3 text layer into a
**bounded stack of static overlay layers** with deterministic ordering,
anchored placement, and per-layer visibility/opacity modeling — while
keeping H3 runtime output byte-identical when only one layer is active.

Status: IMPLEMENTED exactly as specified below in the commit that
carries this flip (`git log -- docs/H4_LAYER_STACK_DESIGN.md`).
Authorization was granted with the H4 task order (implement without
waiting for S25 validation); all hardware-dependent validation is
PENDING and no visual/device claim is made.

## 0. Authorization and gates

- H2 S25 validation: STILL PENDING (user-side). H3 S25 validation: STILL
  PENDING. Neither gates H4 code-level work per the H4 task order.
- CI success is not device validation. If pending device gates fail,
  only the failing issue is investigated; H4 overlay claims wait on §11.

## 1. Objective

Introduce a static multi-layer overlay stack into `SceneCompositorGL`:

- Up to `MAX_STATIC_OVERLAY_LAYERS` (4) ordered static overlay layers.
- Layer ordering by (`zOrder`, `id`) via the existing `SceneLayer` /
  `SceneComposition` contracts — H4 makes the composition the genuine
  runtime draw-order substrate (it is currently test-only; see H3 doc
  §2 as amended by H4).
- Anchored placement (four corners + center), margin/clamp policy
  reused from H3, per-layer alpha + enabled flag.
- Deterministic, bounded GL rendering: one shared RGBA program, one
  texture + quad per active layer, fixed preallocated slots, no
  per-frame allocation, EGL-step teardown of every slot.
- Runtime activation extends the existing default-off seam: `scene_text`
  (H3, top-left, unchanged) + new `scene_text2` (bottom-left). Absent
  content on both = H2 passthrough, zero GL delta.

## 2. Scope (exact)

- New pure module `SceneOverlayStack`: anchor enum, overlay spec
  validation, stack resolution to an ordered draw list (rect + NDC +
  alpha + z), stack bound enforcement. Reuses `SceneTextLayerLayout`
  (size/margin/NDC), `SceneCanvas`/`SceneRectPx`, `SceneLayer` /
  `SceneComposition` — no contract changes.
- Text content only in H4 (measured by the Android shell with `Paint`,
  exactly like H3). Per-layer alpha and `enabled` are fully modeled and
  tested in the pure core; runtime fixes alpha=1.0 and derives
  visibility from content presence (blank/absent = layer absent),
  pending the H5 config surface (documented, not hidden).
- GL: slot-based multi-quad renderer in `SceneCompositorGL`, reusing the
  H3 RGBA program sources byte-identically (renamed to overlay-* for
  truthfulness — the program was always a generic RGBA×alpha program).
  Single-layer output identical to H3 by construction (same raster,
  upload, quad, blend); pinned by a TOP_LEFT equivalence test.
- Seam: `scene_text2` extra, service → `SceneSource(subtextContent)` →
  compositor; MainActivity forwarding; length-only logging.
- Tests: pure stack tests (ordering, anchors/bounds/clamp, visibility/
  alpha, invalid configs, cap, determinism, H3 equivalence) + two
  lifecycle structure tests (fallback retry preserves extras;
  revocation guard) + teardown-order contract unchanged (slots die
  inside the EGL step).
- Docs: this file (spec → implemented flip on completion), one-line
  forward pointer in the H3 doc §2.

## 3. Explicit exclusions (not H4 — H5 candidates)

- Image/bitmap layers: evaluated and deferred. Needs a Bitmap-provider
  seam (content URIs? resources? intent parcels?) and a config surface;
  both are H5 decisions. The stack resolves `TEXT` entries only; other
  content kinds are rejected as invalid (loud, never silent).
- Animation, per-frame content changes, alerts/widgets, scene
  switching, multi-scene management.
- UI, preferences, editor, permissions.
- RTMP/RootEncoder/H.264/audio-routing, watchdog/recovery/session,
  projection-ownership, diagnostics/status-contract changes.
- C++/NDK, new dependencies.
- Any change to H3 single-layer output or the default ScreenSource path.

## 4. Reuse map (no reinvention)

| Need | Existing foundation |
|---|---|
| Capture + VD + projection callback | `SceneSource` (one added ctor param) |
| Frame offer/draw/swap/complete path | `onFrameAvailable` (one conditional loop over ≤4 slots) |
| Layer identity/ordering/validation | `SceneLayer`, `SceneComposition` (first genuine runtime use) |
| Size/margin/NDC math | `SceneTextLayerLayout.textSizePxFor/marginPxFor/ndcQuad` |
| TOP_LEFT placement | `SceneTextLayerLayout.layout` (H4 delegates; equivalence pinned) |
| Lifecycle/window/teardown decisions | Controller + six-step ledger (unchanged) |
| RGBA blend program sources | H3 GLSL, byte-identical, renamed |
| Activation seam pattern | `scene_text` extra + forwarding (mirrored) |

## 5. Pure module: `SceneOverlayStack` (new, JVM-tested)

- `SceneOverlayAnchor`: `TOP_LEFT`, `TOP_RIGHT`, `BOTTOM_LEFT`,
  `BOTTOM_RIGHT`, `CENTER`.
- `SceneOverlaySpec`: validated value object — `id` (non-blank), `text`
  (non-blank, ≤ `MAX_TEXT_LAYER_CHARS`), `anchor`, `zOrder` (≥ 0),
  `marginPx` (≥ 0), `maxWidthPx` (> 0), `alpha` (NaN invalid, else
  coerced to [0,1]), `enabled`. `of()` returns null on any violation
  (same convention as `SceneComposition`/`SceneTextLayerSpec`), never
  throws.
- `SceneOverlayStack.resolve(canvas, layers)`: `layers` pairs each spec
  with its shell-measured `SceneSizePx`. Returns the ordered
  `SceneOverlayDrawEntry(id, zOrder, rectPx, ndcQuad, alpha)` list, or
  null when: canvas invalid, layer count > `MAX_STATIC_OVERLAY_LAYERS`
  (4), any measurement invalid, any placement has no room, or the
  backing `SceneComposition.of` rejects (duplicate ids — the pure
  backstop). Empty input → empty list (passthrough, not an error).
  Disabled specs are filtered before composition (absent from the draw
  list, like H3's `enabled=false` → null).
- Placement: `TOP_LEFT` delegates to `SceneTextLayerLayout.layout`
  (equivalence test-pinned). Other anchors reuse the same width/height
  capping, then place: right/bottom edges at canvas−margin, center with
  floored bias (leftover pixel goes right/bottom, matching the
  `SceneLayoutCalculator` remainder rule), all clamped inside the
  canvas inset. No placement can produce an out-of-bounds rect.
- Ordering: screen `z=0` + overlay layers as `SceneLayer(id, id, z)` →
  `SceneComposition.of` → `visibleLayersInDrawOrder` → overlay entries
  in that order. Total, deterministic, duplicate-safe.
- No threads, no Android types, no I/O, no allocation beyond the
  returned list (GL calls once at init, never per frame).

## 6. GL integration: `SceneCompositorGL` (extended, device-gated)

- Fields: `overlaySlots` — fixed `Array(MAX_STATIC_OVERLAY_LAYERS)` of
  preallocated slot holders (`textureId`, `quadBuffer`, `alpha`,
  `bitmap`), `overlaySlotCount` (used prefix length), shared
  `overlayProgram` + attribute/uniform handles. H3 single-text fields
  removed; no other state added.
- Init (GL thread, inside the existing latch): build specs from
  (`textContent` → TOP_LEFT z=1, `subtextContent` → BOTTOM_LEFT z=2,
  skipping blank/absent); measure each with one `Paint` (same settings
  as H3); `resolve`; empty → return (passthrough, program never built);
  else build the shared program once, then per entry raster → upload
  (same params as H3) → quad → fill slot. Any failure throws loudly →
  existing start-failure path (partial teardown + rethrow → H2
  one-shot fallback). A half-built stack never reaches the frame path.
- Frame (GL thread): after the passthrough draw,
  `for (i in 0 until overlaySlotCount) drawOverlaySlot(slots[i])` —
  indexed loop, no iterator allocation; each draw is the H3 blend draw
  with slot fields. Blend enabled per draw, disabled after; program,
  attribs, texture unit fully re-specified per draw. Exceptions join
  the existing `failFrame` path. Zero layers → loop skipped → H2 path.
- Cleanup (GL thread, inside the EGL teardown step, before OES
  destruction): per used slot — delete texture, recycle bitmap, null
  quad; then delete the shared program; reset count to 0. Reverse of
  creation, exception-isolated per step like all teardown. No
  use-after-free: slots die before context destruction, on the GL
  thread, before the thread quits (ledger order unchanged).
- Texture/memory bounds: ≤ 4 textures (one per layer, each ≤ canvas
  inset), ≤ 4 small bitmaps held only between init and EGL teardown,
  one program. No queues, no caches, no per-frame allocation.

## 7. Lifecycle / teardown changes

None to the ledger: slots die inside `releaseEgl`, same step that owns
the OES program (H3 §7 rationale unchanged). The six-step order and
`performTeardown` contract are untouched; the controller test's
text-inside-EGL-step contract still holds (now covering all slots).

## 8. Activation and diagnostics

- `scene_source=true` + `scene_text` set → one overlay (H3 behavior).
- `scene_source=true` + `scene_text2` set → bottom-left overlay added
  (z=2, drawn after). Either may be used alone.
- Both absent/blank → pure passthrough; `overlaySlotCount=0`, the
  shared program is never built.
- Logging sanitized (lengths only, never content). No new diagnostics
  ids, no status-contract change: fallback/ready/failure lines from
  H2/H3 already cover the stack (a stack failure is a SceneSource
  start failure).

## 9. File-level change plan

1. NEW `docs/H4_LAYER_STACK_DESIGN.md` — this spec (flipped to
   IMPLEMENTED on completion; SHAs via `git log` on this file).
2. NEW `scene/SceneOverlayStack.kt` — §5 pure core.
3. NEW `scene/SceneOverlayStackTest.kt` — ordering, anchors, clamp,
   visibility/alpha, invalid, cap, determinism, H3 equivalence.
4. EDIT `scene/SceneCompositorGL.kt` — §6 slots; H3 text fields →
   slot array + shared program; init/draw/teardown per §6.
5. EDIT `scene/SceneSource.kt` — `subtextContent` ctor param (default
   null), forwarded to the compositor; sanitized start log extended.
6. EDIT `capture/RtmpBroadcastService.kt` — `EXTRA_SCENE_TEXT2` const,
   read, selection-log suffix, forward to `SceneSource`.
7. EDIT `MainActivity.kt` — forward `scene_text2` launch extra.
8. EDIT `capture/StreamingLifecycleStructureTest.kt` — fallback retry
   preserves extras (`Intent(intent)` copy); SceneSource revocation
   guard (`registerCallback` + `onProjectionRevoked`).
9. EDIT `docs/H3_TEXT_LAYER_DESIGN.md` — one-line §2 forward pointer
   (composition becomes runtime-consumed in H4).

No other files. In particular: no RootEncoder/transport/encoder/audio/
watchdog/recovery/projection/diagnostics/status/overlay/workflow/
dependency changes.

## 10. Risks and mitigations

| Risk | Mitigation |
|---|---|
| H3 single-layer regression | Same raster/upload/quad/blend code path; TOP_LEFT equivalence test; review diff hunk-by-hunk; S25 H3 criteria re-run (pending) |
| GL state contamination between slots | Per-draw full state re-specification (program, attribs, unit 0, blend on/off); indexed loop; inspection checklist |
| Slot/texture leak on partial init failure | Throw → existing teardown → EGL step deletes every used slot (count-gated loop); bitmap recycle per slot |
| Unbounded layers/textures | Hard cap 4, enforced in pure `resolve` (loud null → start failure); fixed array, no lists on the frame path |
| Config-format sprawl (`scene_text3`…) | Only ONE new extra; richer configuration deferred to H5 config-surface decision (documented, not invented here) |
| New Android/GL API shapes | None — every call reuses H3-verified AOSP shapes; stub-compile + CI Android compile prove it |

## 11. S25 validation criteria (PENDING — do not claim)

With `scene_source=true` + `scene_text="MGS • LIVE"` + `scene_text2=
"MGS • H4"`: (1) top-left text identical to H3; (2) bottom-left second
line visible, correct glyphs/placement; (3) z-order correct where
overlapped (n/a for corners — overlap only testable via H5 config);
(4) passthrough parity with both absent; (5) 10-minute run, no leak/
freeze; (6) H3 §11 re-run green. Logcat: requested/selected lines +
`overlay ready: N layers` with lengths only.

## 12. H5 proposal (not started)

Image/bitmap overlay layers + a real layer-config surface (single
structured extra or debug-only provider — decision needed), per-layer
alpha/visibility/tint at runtime, solid-color backing layers, overlap/
z-order device proof. Builds on the H4 stack without changing it.
