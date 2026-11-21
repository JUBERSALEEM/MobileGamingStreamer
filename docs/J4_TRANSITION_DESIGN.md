# J4 — Scene Transitions & Media Integration

Status: IMPLEMENTED (17 new tests; suite 636/636 on both JVM harnesses; neg control green).

## 1. Investigation findings

- **Scene model/persistence** (`SceneConfig.kt`, `ScenePrefsStore.kt`):
  codec v6 (19 fields, animation 19th); scenes in `mgs_scenes`
  SharedPreferences, write-through, self-healing; MainActivity owns
  state; `activeSceneEncoding` builds the live-push payload.
- **Live switching** (`RtmpBroadcastService.updateScene`): `ACTION_UPDATE_SCENE`
  broadcast → pure `SceneSwitchPlanner.resolve` → NOOP / IN_PLACE
  (`SceneSource.updateScene` → `gl.rebuildOverlays` + rollback to
  `lastSpecs`) / SWAP_TO_SCENE/SCREEN (library `changeVideoSource`,
  new source object). Sole sender: MainActivity.onActiveScene
  (`requestSceneUpdate`). Layer edits never push live. Capture,
  encoder, RTMP, audio untouched by the scene path.
- **GL** (`SceneCompositorGL`, single EGL context on a HandlerThread):
  input ST → OES passthrough (identity MVP + ST matrix) → slots (2D
  blend program with uAlpha but NO MVP; video = style quad + OES quad)
  → presentation-time + swap. Rebuild rendezvous on the GL queue (no
  half-built draws). Release funnel `releaseOverlaySlotsLocked`
  (tickers canceled, players stop+release with bounded rendezvous,
  textures/STs/programs deleted) runs first inside `releaseEglLocked`.
- **H8 rollback**: rebuild failure → best-effort plain rebuild of
  `lastSpecs` → clean passthrough if that also fails. Never throws.
- **J1 geometry**: FIT/FILL resolved at measure/UV/bitmap time —
  post-composite work cannot disturb it.
- **J2 budget**: `MAX_CONCURRENT_VIDEO_LAYERS = 2`, decided once per
  build, over-budget skipped loudly. Player owns a HandlerThread;
  callbacks on it; ST listener on the GL handler; teardown stops the
  player BEFORE releasing its ST. Release-before-build order inside
  every rebuild.
- **J3**: pure core (presets, elapsed clock, easings, pixel→NDC
  geometry incl. `writeTransformedNdc`), per-slot clocks, 60 Hz tick
  armed-when-active, shared coalesced motion redraw, preview sampler.
- **Editor/preview**: stateless `SceneEditorPage`, activity-owned
  state; gated preview ticks; honest static/animated/live labels.
- **CI**: `dev-ci.yml` skips validation while a PR is open
  (`detect_pr`); `android-build.yml` validates PRs (unit tests, lint,
  release APK, signing verify) and uploads the artifact; the
  GitHub-Releases publication step skips on PRs. No local SDK:
  kotlinc-JVM harness + K2.2.20 gate locally, Android compile in CI.
- **CRITICAL prior finding**: the H2 S25 device gate is STILL PENDING
  — no GL pixel/orientation behavior has ever been device-verified.
  J4 derives snapshot orientation from GL-spec-defined semantics
  (`glCopyTexSubImage2D` row mapping) and does NOT depend on the
  unproven overlay-upload orientation either way (transitions are
  self-consistent: the snapshot freezes exactly what the framebuffer
  holds). AOSP `GLUtils.java` shows no Java-side flip; the native
  path was not resolved — the overlay-orientation question stays
  exactly where it was (pending S25 gate), untouched by J4.

## 2. Why not dual live compositions (budget investigation, §5)

Retaining two COMPLETE scene compositions (old slots + new slots,
both live) was evaluated and REJECTED:

- Decoder budget: two scenes × up to 2 video layers = up to 4
  concurrent MediaPlayers, doubling the J2-budgeted decoder/RAM/thermal
  envelope with no evidence any device sustains it. Freezing (not
  releasing) outgoing players still holds decoders + STs + textures.
- Ownership: two slot tables need a second teardown order, a second
  ticker domain, and cross-table failure semantics — a rewrite of the
  H8/H10/J2 ownership map for a 0.2–2 s effect.
- No second EGL context is allowed, so both tables would share one
  context's texture namespace with no isolation.

ADOPTED: **snapshot transition**. The retained outgoing state is
exactly ONE RGBA texture (composed outgoing frame, GPU-side copy, no
CPU readback, no bitmap). At every instant at most ONE scene's slots
(≤2 players) exist, so the J2 decoder budget proof is unchanged and
release-before-build order is preserved. Outgoing video freezes as
its last composed frame inside the snapshot — standard NLE behavior,
documented, no extra decoder held.

## 3. Architecture

### 3.1 Pure core (JVM-tested) — `SceneTransition.kt`

- `SceneTransitionType`: CUT, CROSSFADE, FADE_THROUGH_BLACK,
  SLIDE_LEFT/RIGHT/UP/DOWN, WIPE_LEFT/RIGHT/UP/DOWN, ZOOM_IN/ZOOM_OUT
  (13). Slide/wipe direction = direction of on-screen motion.
- `SceneTransition(type, durationMs, easing)` with validating `of()`
  (duration 200–2000 ms; garbage → null) and token `encode/parse`
  (`"TYPE,duration,EASING"` for prefs + intent extra). DEFAULT = CUT
  (today's behavior; duration/easing ignored, never rendered).
- Clock: `progressAt(elapsedMs)` → clamped 0..1 (≥1 = done); easing
  REUSES J3 `SceneAnimation.ease` (no duplicate easing tables).
- Curves (all pure, all pinned): crossfade alpha `1-e`; FTB phase
  split (e<0.5 outgoing-over-black, e≥0.5 incoming-under-lifting-black);
  wipe scissor rects in GL Y-up framebuffer coords (clamped, never
  negative/overflowing); zoom scales (IN 1→1.2, OUT 1→0.8, alpha
  1→0); slide offsets REUSE J3 `SLIDE_OUT_*` transforms on the
  fullscreen rect (fully-off at e=1, both orientations).
- Budget: `snapshotBytesFor(w,h)` + `allowsSnapshot(w,h)` with
  `MAX_TRANSITION_SNAPSHOT_BYTES = 16 MiB` (covers 1440p RGBA; beyond
  → CUT fallback + log). Service-side `parseExtra(: String?)`:
  null/garbage → CUT, never throws.
- `TransitionProgress(config, startElapsedMs)` + `sampleAt(nowMs)` →
  (eased progress, done): the single clock both GL and the editor
  demo evaluate (same-elapsed → same-progress by construction).

### 3.2 GL integration (device-gated)

- State: `transTextureId` (snapshot), `transBlackTexId` (1×1 black,
  FTB only), `transConfig`, `transStartElapsedMs`, `transW/H`,
  reused `transScratchNdc`/`transScratchArr` (snapshot quad; the 2D
  blend program + shared fullscreen UVs are reused — snapshot
  orientation is spec-correct with them, §1).
- Entry `transitionToScene(specs, transition)` (rendezvous, never
  throws): CUT → plain `rebuildOverlaysLocked`. Else guards
  (frameSequence>0, snapshot budget) → `composeCurrentVisualLocked()`
  (no present, no controller counting — re-presentation like the
  motion redraw) → `glCopyTexSubImage2D` into the snapshot texture →
  release + build incoming → set state → arm tick. Any failure before
  state-set: delete snapshot, clear, return false → caller runs the
  EXISTING rollback (CUT to `lastSpecs`).
- Present paths (input + motion redraw) share an extracted
  `drawComposedSceneLocked()` (clear + passthrough + slots), then:
  no transition → present; WIPE_* → clear, snapshot fullscreen,
  scissor-guarded (`try/finally` disable) composed draw, present;
  others → composed draw + top layer (snapshot quad via J3
  `writeTransformedNdc` into scratch / black overlay), present.
  Blend state mirrored from the slot draws per draw.
- Tick: `armAnimTickLocked` also arms while a transition is active;
  `onAnimTickLocked` ends transitions (delete textures, clear) BEFORE
  the final clean redraw. Input frames arriving mid-transition sample
  progress per draw (elapsed clock — frame-rate independent).
- Rapid switch: re-entry composes the CURRENT visual (incl. in-flight
  layer at now-progress) into the REUSED snapshot texture (realloc
  only if dims changed) → release + rebuild → new clock. Still one
  texture, still ≤2 players. Rollback target unchanged (`lastSpecs` =
  last fully built scene — always valid).
- Cancel rule: ANY plain rebuild (`rebuildOverlaysLocked`,
  `releaseOverlaySlotsLocked`) deletes the snapshot and clears state —
  rebuild-during-transition is a deterministic CUT (covers edits if
  ever pushed live, and the rollback path itself).
- Teardown: the existing funnel deletes the snapshot first inside
  `releaseEglLocked`; tickers die with the existing removals.
  Stop/revoke/destroy mid-transition need no new path. No RTMP,
  audio, capture, or encoder touch — output dims/session unchanged.

### 3.3 Service + source (delivery)

- `EXTRA_SCENE_TRANSITION` rides `ACTION_UPDATE_SCENE`;
  `requestSceneUpdate(context, config, transition?)` (default null =
  CUT). Service parses via the pure helper; IN_PLACE passes the
  config to `SceneSource.updateScene(newConfig, transition)` (default
  CUT — signature-compatible). SWAP actions IGNORE the transition
  (documented: cross-source blending would need dual capture
  sessions; swaps stay CUT, exactly today's behavior). Fresh-source
  start never transitions (nothing to transition from).

### 3.4 Editor + demo (honest tiers)

- Global `TransitionCard` on the SCENES page (top, above the scene
  list): 13-type FlowRow picker, duration slider (0.2–2 s, hidden
  for CUT), easing picker (reuses the J3 label map), all persisted
  write-through in `ScenePrefsStore.KEY_TRANSITION` (garbage →
  CUT). One callback `onSceneTransition(SceneTransition)`.
- Demo: separate compact canvas (editing scene, simplified boxes —
  rounded rect + text/kind label at `ScenePreviewBoxes` positions —
  over black, driven by the SAME pure `TransitionProgress` clock,
  plays once per tap at 50 ms). Labeled EXACTLY: simplified layout,
  entrance over black, live switches blend two live scenes, timing /
  direction / easing match. Static preview untouched; PREVIEW — NOT
  LIVE untouched. No claim that the demo proves live correctness.

## 4. Transition semantics (pinned by tests)

- CUT: instant (existing rebuild; no snapshot, no tick).
- CROSSFADE: snapshot α 1→eased-0 over live incoming.
- FADE_THROUGH_BLACK: e<0.5 black + snapshot α(1−2e) (incoming build
  underneath, skipped draws); e≥0.5 incoming + black overlay α(2−2e).
  Single rebuild at start (uniform lifecycle).
- SLIDE_d: outgoing exits toward d (snapshot translated, fully off at
  e=1), incoming revealed beneath. Content moves toward d.
- WIPE_d: wipe edge travels toward d; incoming revealed where the
  edge has passed (scissor rects, Y-up).
- ZOOM_IN: snapshot 1→1.2 about center + fade (dive through old into
  new). ZOOM_OUT: snapshot 1→0.8 about center + fade (old shrinks
  away).
- J3 interplay: outgoing anims freeze in the snapshot; incoming anims
  start at rebuild and play through the blend (independent clocks,
  tested). Mixed text/image/video/widget content is agnostic BY
  CONSTRUCTION (post-composite); portrait + landscape via output dims.

## 5. Lifecycle + failure matrix

| Event | Behavior |
|---|---|
| Switch, no live session | Service no-session guard (existing) |
| CUT switch | Existing rebuild + rollback, snapshot cleared |
| Transition switch | Snapshot → rebuild → blend → auto-end + cleanup |
| Rapid switch mid-transition | Re-snapshot current visual, rebuild newest, restart clock |
| Rebuild fails | Snapshot deleted, CUT rollback to `lastSpecs` (existing) |
| Rollback fails | Clean passthrough (existing), no transition state |
| Edit pushed mid-transition | Cancel → CUT to rebuilt (deterministic) |
| Stop/revoke/destroy mid-transition | Existing funnel deletes snapshot; tickers canceled |
| No frames yet / over budget / no GL program | Degrade to CUT + log (never a broken blend) |
| SWAP_TO_SCENE/SCREEN | Transition ignored, CUT swap (documented boundary) |
| Orientation/dims change | Layout fixed per source; dims-mismatch realloc guard |

Transition state is three ints + two small objects + one texture —
deterministic and bounded; no queues, no threads, no I/O on the path.

## 6. Compatibility

- Scene codec UNTOUCHED (v6; transition is app-global, not per-scene
  — no v7 bump, all v6 pins hold byte-identical).
- Default CUT = today's behavior for every existing flow (fresh
  start, legacy callers, missing/garbage extras or prefs).
- J1 FIT/FILL, J2 decode/OES/budget, J3 math/timing preserved (J3
  functions reused, not copied; no J1–J3 file semantics changed).
- `updateScene`/`requestSceneUpdate` gain defaulted params only.

## 7. Orientation + coordinates

- Snapshot sized to `layout.outputWidth/Height` (already the
  portrait/landscape-resolved output); slide/zoom derive from the
  same dims through the J3 fullscreen-rect path.
- Wipe scissors are computed in GL Y-up framebuffer coords
  (row 0 = bottom) — pinned by tests both orientations. This is the
  one coordinate subtlety; double-checked against the `glScissor`
  contract, device-confirmed at the S25 gate.

## 8. Honest limits (device-gated, never claimed from CI)

Snapshot-copy orientation (spec-derived), scissor/present sequencing,
in-flight chaining smoothness, completion timing, teardown deletion,
CUT-fallback triggers, and no-interruption of RTMP/audio/encoder are
proven by Android compile + lint + packaging in CI and DEFERRED to the
S25 gate (after 7 PM UAE) with the already-pending H2 gate. JVM tests
pin every pure computation both orientations.

## 9. Build order

1. Pure `SceneTransition.kt` (+ `TransitionProgress`, budget, parser).
2. `SceneTransitionTest` (~20 tests); harness wiring; 619 + N green.
3. GL snapshot + transition render + tick + cancel/funnel deletes.
4. Source/service delivery (extra, parse, pass-through, SWAP ignore).
5. Prefs + MainActivity state + editor card + demo.
6. Gates → commit → push → CI (exact SHA) → artifact + signing.
