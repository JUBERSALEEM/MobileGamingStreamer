# H8 — Live Scene Switching

Status: IMPLEMENTED (526 JVM tests green, CI on exact HEAD).

Implementation notes (review amendments, all within scope):
- `ACTION_UPDATE_SCENE` is handled on both delivery paths (command
  receiver + `onStartCommand`, mirroring the bitrate action); the
  no-session branch stops itself only on the start path (the
  receiver path has no start-id and the service is alive by
  definition there).
- Swaps release only the unused replacement on failure — the old
  source was already restarted by the library and stays retained.
- Drive-by fix (pinned): the H6 GL build filtered `text.isBlank()`,
  which dropped every image layer (image specs carry empty text by
  construction); the shared H8 build filters TEXT blankness only.
  The editor preview never had this bug (kind-aware from H6).

## 0. Goals / non-goals

- Switch the active saved scene mid-stream without restarting
  capture, the encoder, RTMP, or audio.
- Hard-cut transition (atomic, OBS-style cut — documented; no fade).
- Non-goals: animated transitions, preview-before-switch UI,
  mid-stream ADB legacy path (start-time only, unchanged).

## 1. Decision matrix (pure, tested)

- New pure `SceneSwitchPlanner.resolve(current, newConfig)`:
  current ∈ {SCREEN, SCENE_CONFIG(config), SCENE_LEGACY},
  new ∈ {scene-with-specs, none}.
- SCENE_CONFIG → same config: NOOP. SCENE_CONFIG → other scene:
  IN_PLACE overlay rebuild. SCREEN → scene or SCENE_LEGACY →
  scene: SWAP via library `changeVideoSource` (its failure path
  restarts the old source automatically — verified in RootEncoder
  2.8.1 `StreamBase`). Any → none: SWAP_TO_SCREEN when currently
  scenic, else NOOP.
- Invalid/empty requested scenes resolve to "none" through the
  existing `streamableSpecs` gate: never a broken state.

## 2. Service (main thread — commands already serialize)

- Retain `activeVideoSource` beside `display` (set together,
  nulled at all four `display = null` sites: start head,
  disconnect, stop, destroy).
- New `ACTION_UPDATE_SCENE` (+ `EXTRA_SCENE_CONFIG`, absent = none):
  unknown action before → handled now. No session → status message
  + `stopSelf(startId)` (never leaves an idle service).
- `updateScene(config)` executes the planner action: IN_PLACE via
  `SceneSource.updateScene` (false → status message, old overlays
  kept or rolled back); SWAP via `changeVideoSource` in try/catch
  (throw → status message; library already rolled back to the old
  running source). Retain the new source on success.
- Capture/encoder/RTMP/audio untouched: no `prepare`, no `release`,
  no projection handling on this path. Stop/revocation/teardown
  paths unchanged.

## 3. SceneSource in-place update

- `currentSceneConfig` (public get, private set) + `lastSpecs`
  (last good overlay list). `updateScene(newConfig): Boolean`:
  rejects non-started sources and empty spec lists; otherwise
  `gl.rebuildOverlays(specs)`; on false, best-effort rebuild of
  `lastSpecs` (rollback to old overlays, still live on failure →
  passthrough). Success updates both fields.
- Only config-started sources update in place; legacy-started
  sources switch via library swap (old source rollback).

## 4. GL rebuild (GL thread rendezvous, atomic vs frames)

- Extract `releaseOverlaySlotsLocked()` (slots + program delete,
  shared with EGL teardown) and `buildSlotsLocked(requests, ...)`
  from init; `rebuildOverlaysLocked(specs)` = release + build.
- Public `rebuildOverlays(specs): Boolean` via the existing
  `runOnGlThread` rendezvous: false on timeout/thread-gone/build
  throw. Frames and rebuilds share the GL handler queue, so no
  frame can draw a half-built stack. Textures/bitmaps of replaced
  slots are deleted/recycled before new ones upload (no leak, no
  stale resources).
- Rapid switches serialize: service commands run in main-thread
  order; each rebuild completes (or times out) before the next
  starts — latest-wins by ordering, no queue, no coalescing loss
  (every command is a full state transition).

## 5. Activity/UI

- Pressing USE while streaming persists (as before) AND pushes
  `ACTION_UPDATE_SCENE` with the fresh active encoding (or none).
  Not streaming → persist only (next GO LIVE picks it up, H5
  behavior preserved). Service-side no-session guard is
  authoritative; the activity flag is a courtesy.

## 6. Compatibility

- ScreenSource passthrough, audio, Stop, revocation, fallback, and
  the H2–H7 start paths are untouched. In-place updates never run
  for legacy-started sources. No new permissions/dependencies.

## 7. Tests

- `SceneSwitchPlannerTest` (pure): full decision matrix incl.
  same-config NOOP, invalid→none, legacy→swap.
- Structure pins: service retains/handles update action;
  SceneSource.updateScene delegates to `gl.rebuildOverlays`;
  rebuild releases before building; activity pushes on USE.
- Full gates: 514+ JVM suite, K222, CI on exact HEAD.

## 8. Build order

1. `scene/SceneSwitchPlanner.kt` (NEW, pure) + tests.
2. GL extract + rebuild.
3. SceneSource update path.
4. Service action + retention + dispatch.
5. Activity push.
6. Gates + commit + push + CI.
