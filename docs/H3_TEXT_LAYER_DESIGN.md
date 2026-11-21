# H3 Design: First Static Text Overlay (IMPLEMENTED)

Technical design + file-level change plan for the first visible scene
layer. Implemented exactly as specified below in the commit that carries
this flip (`git log -- docs/H3_TEXT_LAYER_DESIGN.md`); authorization was granted with the H3 task order, and the H2 device
gate remains user-pending — no device validation is claimed.

## 0. Gate status (binding)

Gate record (amended at implementation; the original plan required both
gates before any runtime code):

1. H2 S25 physical-device validation (H2 §11 checklist) — STILL PENDING,
   user-side. The H2+H3 task order explicitly authorized implementing H2
   fixes + H3 now and deferring device validation, so this gate now guards
   validation claims only: no S25/compositor/device claim is made.
2. User authorization of H3 implementation — GRANTED with the H2+H3 task
   order (implementation gate lifted; validation gate kept).

CI success is not device validation. If the pending H2 gate fails on the
S25, only the failing H2 issue is investigated; H3 overlay-correctness
claims wait on the H3 S25 criteria (§11).
If the H2 gate fails, H3 stays unimplemented and only the failing H2 issue
is investigated.

## 1. Objective

Introduce the first basic visible overlay layer — a minimal static text
overlay — into the existing `SceneCompositorGL` pipeline while preserving
the original screen passthrough bit-for-bit when the layer is off.

## 2. Scope (exact)

- One static text layer: fixed string, fixed placement, fixed alpha for the
  whole session (no per-frame text changes, no measurement on the frame
  path).
- Rendered as a blended RGBA quad on top of the existing fullscreen OES
  passthrough quad, inside the current GL-thread frame path
  (offer → draw → present-time → swap → complete, unchanged).
- Deterministic pure-Kotlin layout (bounds from canvas + margins + measured
  text size passed in) and alpha/opacity handling (coerced range).
- Explicit layer ordering via the existing `SceneLayer`/`SceneComposition`
  contracts (passthrough `zOrder=0`, text `zOrder=1`); the pure H3 core
  models the order (`compositionFor`, pinned by tests) and the GL frame
  path implements it (OES draw, then text draw). `SceneLayer`/
  `SceneComposition` stay metadata-only at runtime — no GL-thread model
  consumption, by design (static layer, zero per-frame model traffic).
  H4 update: the H4 stack makes `SceneComposition` the genuine runtime
  draw-order substrate (`SceneOverlayStack.resolve`, init-time only); the
  H3 single layer still performs zero per-frame model traffic.
- Activation strictly through the existing default-disabled `SceneSource`
  test seam: no overlay renders unless the SceneSource path is explicitly
  activated AND text content is provided (absent content = passthrough).
- `ScreenSource` remains the normal default path, untouched.
- Pure JVM tests for layout, opacity, bounds, ordering; teardown-ledger
  test updates; documentation of rendering ownership, lifecycle, cleanup.

## 3. Explicit exclusions (not H3)

Camera/media-source layers, animated layers, alerts/widgets/browser
sources, scene editor/preferences UI, scene switching, multi-scene
management, new permissions, RTMP/RootEncoder/H.264/audio-routing changes,
watchdog/recovery/session-isolation/projection-ownership changes, C++/NDK,
new dependencies. No expansion beyond the first static text overlay.

## 4. Reuse map (no reinvention)

| Need | Existing foundation (reused as-is unless noted) |
|---|---|
| Capture + VD + projection callback | `SceneSource` (unchanged) |
| Frame offer/draw/swap/complete path | `SceneCompositorGL.onFrameAvailable` (extended with one conditional quad, §6) |
| Layer identity/ordering/validation | `SceneLayer`, `SceneComposition` (first runtime use; no contract change) |
| Canvas/bounds/placement math | `SceneCanvas`, `SceneRectPx`, `SceneLayoutCalculator`, `SceneLayoutPolicy` |
| Display/output sizes | `SceneSourceLayout` |
| Lifecycle/window/teardown decisions | `SceneCompositorController` + `SceneTeardownLedger` (one added step, §7) |
| Activation seam pattern | `EXTRA_SCENE_SOURCE` default-false extra + MainActivity forwarding |

## 5. Pure module: `SceneTextLayer` (new, JVM-tested)

- `SceneTextLayerSpec`: validated value object —
  `text` (non-blank, bounded length, e.g. ≤ 64 chars),
  `marginsPx`, `maxWidthPx`, `alpha` coerced to 0.0–1.0,
  `enabled` flag. Invalid input → null (same `of()` convention as
  `SceneComposition`), never throws, never a half-built layer.
- `SceneTextLayerLayout`: given (`SceneCanvas`, measured text size in px
  supplied by the Android shell, spec) returns a `SceneRectPx` destination
  clamped inside the canvas. Text *measurement* stays Android-side (needs
  `Paint`); rect math stays pure and tested.
- Ordering: composition `[screen(z=0), text(z=1)]` yields draw order
  back-to-front via `visibleLayersInDrawOrder`; disabled text (or absent
  content) reduces to passthrough with zero GL delta.
- No threads, no Android types, no I/O — same discipline as H0/H1/H2 pure
  modules.

## 6. GL integration: `SceneCompositorGL` (extended, device-gated)

- Init (GL thread, inside existing `start()` latch, strict like all init):
  if text content present, rasterize once with `Canvas`/`Paint` to an
  `ARGB_8888 Bitmap`, generate a 2D (`GL_TEXTURE_2D`, not OES) texture,
  upload once (`GLUtils.texImage2D`), build a second minimal program
  (RGBA sampler + alpha uniform). Raster/upload/program failure → start
  failure via the existing loud path (partial teardown + rethrow), never a
  silent missing layer.
- Frame (GL thread): after the passthrough draw, iff the text layer is
  visible → `glEnable(GL_BLEND)`, `blendFunc(SRC_ALPHA,
  ONE_MINUS_SRC_ALPHA)`, bind text program/texture, set viewport-scissored
  (or translated) quad from the precomputed rect, draw, `glDisable(BLEND)`.
  No per-frame allocation, no text re-raster, no measurement on the path.
  Timestamp (`EGLExt`), swap, and `complete(handle)` in `finally`
  unchanged.
- Failure (per frame): text-draw exception joins the existing `failFrame`
  path (log + `onRenderFailure` + stall surfacing). No new authority, no
  text-specific recovery.
- Cleanup (GL thread, inside the EGL teardown step, before context
  destruction): delete text program/texture, `bitmap.recycle()`, null out.
  Order: text GL objects die with the EGL step (same step that owns the
  OES program today), so no use-after-free across the ledger.

## 7. Lifecycle / teardown changes

- `SceneTeardownSteps` gains one shell-side concern, executed INSIDE the
  existing `releaseEgl` step (text texture/program/bitmap are
  compositor-context objects with the same lifetime as the OES program).
  No new ledger position is needed; the six-step order is unchanged. The
  controller's `performTeardown` contract is untouched.
- Alternative considered and rejected: a seventh ledger step. Rejected
  because same-lifetime objects in the same step cannot interleave wrongly,
  and the fixed order stays minimal. (Revisit only if authorization review
  demands separate exactly-once accounting for the bitmap.)
- `SceneSource.start()` passes the optional text content (if any) into the
  compositor constructor; `stop()`/`release()`/revocation paths unchanged.

## 8. Activation (default-off, two keys)

- Key 1 (existing): `scene_source=true` selects the SceneSource path.
  Key 2 (new, same pattern): optional `scene_text` string extra; absent
  or blank = no text layer = H2 passthrough. Both default off; production
  launches carry neither, so default behavior is byte-identical.
- `MainActivity` forwards `scene_text` exactly like `scene_source`
  (launch-intent extra, no UI, no prefs, no permissions).
- Open decision for authorization review: fixed validation string (e.g.
  `"MGS • LIVE"`) vs free-form `scene_text` content. Recommended:
  `scene_text` with the fixed string as the documented validation value —
  one mechanism, deterministic tests either way.

## 9. File-level change plan (implementation time only)

1. NEW `scene/SceneTextLayer.kt` — spec + layout (§5).
2. NEW `scene/SceneTextLayerTest.kt` — bounds/alpha/ordering/validation
   tests incl. clamping, enabled=false, oversized-text rejection.
3. EDIT `scene/SceneCompositorGL.kt` — text texture/program init, per-frame
   conditional quad, EGL-step cleanup (§6). No frame-path reordering.
4. EDIT `scene/SceneSource.kt` — accept optional text content, pass to
   compositor (ctor param, no lifecycle change).
5. EDIT `capture/RtmpBroadcastService.kt` — read `scene_text` extra,
   forward to `SceneSource` (default-off branch mirrors existing seam).
6. EDIT `MainActivity.kt` — forward `scene_text` launch extra (one block,
   mirrors existing forwarding).
7. EDIT `scene/SceneCompositorControllerTest.kt` — extend teardown-order
   recording test to assert text cleanup runs inside the EGL step.
8. EDIT this doc — flip header from PLAN to IMPLEMENTED (done; SHAs via
   `git log` on this file). Authorization-review amendments: none — the
   `scene_text` free-form extra with documented validation value was
   adopted as recommended. Scope note: the `VIDEO_SOURCE_FALLBACK`
   diagnostic id shipped in the same commit is H2-fallback scope, not H3 —
   the text layer itself adds no diagnostics.

No other files for H3. In particular: no RootEncoder/transport/encoder/
audio/watchdog/recovery/diagnostics/overlay/workflow/dependency changes.

## 10. Automated-test plan

- JVM (harness + CI `testDebugUnitTest`): spec validation (blank/
  oversized/alpha edges), rect math (margins, clamping, oversized canvas
  rejection), ordering (`[screen, text]` and `[text(disabled)]` cases),
  teardown recording (text cleanup inside EGL step, exactly-once,
  restart refresh).
- Compile/lint/readiness/zero-test/APK-signing: full existing gates, no
  weakening. Device-gated GL covered by §11, never claimed from CI.

## 11. Device-validation criteria (S25, user-run, after H2 gate)

With `scene_source=true` + `scene_text` set: (1) text visible, correct
position/size/alpha, no flicker; (2) passthrough pixels unchanged around
it; (3) orientation changes keep text inside canvas; (4) stop/restart
shows no stale text, no leak (repeat 3×); (5) revocation → clean terminal
state; (6) 10-minute stream: no growth, drops steady-state 0; (7) without
`scene_text`: H2 passthrough identical; (8) normal launch: ScreenSource
identical (regression).

## 12. Protected behaviors (must remain byte-identical)

Screen capture/passthrough, RootEncoder 2.8.1 RTMP/H.264, game+mic
routing, floating controls + task-dismiss keep-streaming, projection
lifecycle/revocation, socket retry/deadlines, watchdog/recovery
authority, session isolation, diagnostics/status contract,
accessibility/TalkBack, streaming profiles/controls. Any H3 decision
risking one stops for review instead of guessing.
