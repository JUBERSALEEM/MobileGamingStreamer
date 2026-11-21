# H5 Design: User-Facing Scene Editor & Scene Management (IMPLEMENTED)

First real user-facing scene UI: create/name/select/duplicate/delete
scenes, edit text layers (content, order, visibility, anchor, margin,
max width, opacity), preview the composition, persist across restarts,
and stream the selected scene through the real H4 pipeline — no ADB.

Status: IMPLEMENTED exactly as specified below in the commit that
carries this flip (`git log -- docs/H5_SCENE_EDITOR_DESIGN.md`).
S25 validation stays PENDING; no device claim is made.

Implementation notes (review amendments, all within scope):
- `SceneSource` takes the encoded `sceneConfig` string (not a spec list)
  because its public ctor cannot expose the internal H4 spec type; it
  decodes deterministically via the same codec the service used.
- The codec drops duplicate layer ids (keep first) so corrupt stores
  cannot fail H4 resolution.
- Editor rename/text fields keep raw transient text; the codec repairs
  names and drops invalid layers on persist/delivery.

## 0. Gates

H2/H3/H4 S25 validation still pending (user-side, after 7 PM UAE only —
never request office-hours device testing). CI success is not device
validation.

## 1. Architecture

- Pure model + codec (`scene/SceneConfig.kt`, JVM-tested): `StreamScene`
  (id, name, layers), `StreamSceneLayer` (id, text, anchor, zOrder,
  marginPx, maxWidthPx, alpha, enabled). Versioned text encoding
  (`SceneConfigCodec`, `v1`); cosmetic fields repaired
  (blank name → default, truncated), structural faults drop the entry;
  decode never throws. Bounds: ≤16 scenes, ≤4 layers (H4 cap),
  name ≤40 chars, text ≤64 (H4), margin 0..4096, maxWidth 1..16384,
  finite alpha (coerced by H4 `of()`).
- Android store (`ScenePrefsStore`, thin): SharedPreferences file
  `mgs_scenes`, keys `scenes_v1` + `active_scene_id`. Write-through on
  every mutation; corrupt/missing → seed default scene ("Starter",
  one `MGS • LIVE` top-left layer). No bitmaps/textures stored.
- UI state stays activity-owned (existing pattern): scenes list,
  active/editing ids, delete target, transient error. New `SCENES`
  dock entry + page route; all controls reuse clay components
  (`ClayButton/TextField/OptionGroup/ToggleRow/Dialog/MenuRow`,
  material3 `Slider`).
- Delivery: GO LIVE puts `scene_source=true` + `scene_config` (encoded
  active scene) when a valid non-empty scene is active; else the seam
  stays off (ScreenSource default preserved). Explicit ADB
  `scene_source=true` keeps legacy H2/H3/H4 extras behavior
  (validation override wins when both present — documented).
- Service: parses `scene_config` via the pure codec, maps through
  `SceneOverlaySpec.of` (H4 stays authoritative), drops invalid
  layers; empty → ScreenSource default + log; non-empty → SceneSource
  with `sceneLayers`. Fallback retry preserves via `Intent(intent)`.
- `SceneSource`/`SceneCompositorGL`: new optional `sceneLayers` path;
  legacy two-string path untouched. GL measures each spec's text,
  resolves via `SceneOverlayStack` (cap/dup/room re-checked at
  runtime), per-slot alpha honored (H4 uniform, previously fixed 1.0
  only for lack of config). Sanitized logs (counts/lengths only).

## 2. Editor (SCENES page)

Scene cards: name, layer count, ACTIVE pill; actions USE / EDIT (expand
inline) / DUPLICATE / DELETE (confirm dialog); CREATE SCENE button;
rename via text field in the expanded header. Expanded editor: 16:9
preview, layer list back-to-front with z badges, per-layer: text field
(64 cap), anchor option group (5), margin / max-width / opacity
sliders (bounded), visibility toggle, up/down reorder, delete. ADD
LAYER disabled at 4 with an explanatory note. Transient errors
(e.g. cap reached) shown inline, never crash.

## 3. Preview

`ScenePreviewCanvas`: fixed 16:9 box on a dark canvas, layers drawn
back-to-front with real glyphs (`Paint` measure + native canvas text),
positions from `SceneOverlayStack.placeRect` on a 1920x1080 reference
(deterministic; absolute-px geometry is output-resolution-relative —
labeled), alpha honored, disabled layers as ghost outlines with OFF
tags, z badges, and a permanent "PREVIEW — NOT LIVE" ribbon. Pure
`ScenePreviewBoxes` helper computes normalized boxes (JVM-tested H4
equivalence); the composable only measures + scales + draws.

## 4. Exclusions (not H5)

Image/animated/widget/browser/camera layers, effects, multi-scene
switching mid-stream, editor undo, cloud sync, RTMP/encoder/audio/
service/watchdog/recovery changes beyond the specified seam reads, new
dependencies, C++/NDK.

## 5. File plan

1. NEW `docs/H5_SCENE_EDITOR_DESIGN.md` (this spec; flip on completion).
2. NEW `scene/SceneConfig.kt` — pure model + codec + delivery helpers.
3. NEW `scene/ScenePreviewBoxes.kt` — pure preview-box math.
4. NEW `ScenePrefsStore.kt` — Android prefs wrapper + default seed.
5. NEW `SceneEditorPage.kt` — SCENES page + preview canvas composables.
6. EDIT `AppShell.kt` — SCENES dock entry + route + state/actions.
7. EDIT `MainActivity.kt` — scene state, write-through mutations, GO
   LIVE delivery.
8. EDIT `capture/RtmpBroadcastService.kt` — `EXTRA_SCENE_CONFIG` read,
   validate, select source, sanitized log.
9. EDIT `scene/SceneSource.kt` — `sceneLayers` param + forward + log.
10. EDIT `scene/SceneCompositorGL.kt` — spec-list init path (legacy
    two-string path untouched).
11. NEW `scene/SceneConfigTest.kt` + `scene/ScenePreviewBoxesTest.kt`;
    EDIT `capture/StreamingLifecycleStructureTest.kt` (config delivery,
    no-scene default, SceneSource activation pins).
12. NEW `docs/h5-preview/*.png` — JVM-rendered layout-proof images from
    the real H4 placement (visual evidence; NOT app screenshots).

## 6. Risks and mitigations

| Risk | Mitigation |
|---|---|
| Default-path regression | Seam stays off without a valid scene; ScreenSource branch untouched; structure tests pin |
| Invalid/corrupt scenes crash stream | Codec never throws; service drops invalid layers, defaults when empty; fallback intact |
| UI state desync | Single activity-owned source, write-through prefs, reload on create |
| Preview ≠ renderer | Same `placeRect`/measure path; equivalence tests; 1080p-reference labeled |
| Unbounded storage/allocs | Caps on scenes/layers/chars; fixed GL slots; no per-frame alloc; no bitmaps in prefs |
| No-device UI proof | Layout-proof PNGs from real H4 math (honestly labeled) + full S25 criteria pending |

## 7. S25 validation criteria (PENDING)

Create/rename/duplicate/delete/select scenes; restart restores;
4-layer stream shows both texts placed/ordered; opacity/visibility
honored; no-scene stream = ScreenSource; Stop/revocation/fallback
unchanged; 10-minute run. Never request before 7 PM UAE.
