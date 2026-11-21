# K1 — Scene and Collection Management

Status: COMPLETE (CI exact HEAD, PR #2 open/mergeable/clean).
Scope: collections CRUD + scene membership + default/active collection
semantics + flat-store migration. No K2–K10 work.

## 1. Model: scenes are global, collections reference

- `StreamSceneCollection(id, name, sceneIds)` (`scene/SceneCollections.kt`):
  an ordered, de-duplicated reference list over global scene ids.
- No operation duplicates, edits, or deletes a scene: membership lists
  only. Multi-membership shares one record (identity-tested).
- Virtual All Scenes (`ALL_SCENES_COLLECTION_ID = "all"`): computed, never
  stored; always shows every known scene in store order; non-deletable,
  non-renameable; the safe fallback for every unknown selection.
- Caps mirror scene discipline: `MAX_STORED_COLLECTIONS = 8`,
  `MAX_COLLECTION_NAME_CHARS = 40`, blank → `Untitled collection`.

## 2. Store format v2 (additive)

- `scenes_v1` keeps the exact v6 scene encoding — byte-identical; migration
  never rewrites scene records (migration-fixture test proves it).
- New keys in the same prefs file (`mgs_scenes`): `scene_collections_v1`
  (`collections:v1` tab/escape codec, reusing `SceneConfigCodec`
  escape/unescape, widened `private` → `internal`), `active_scene_collection`,
  `default_scene_collection`.
- Repair policy mirrors the scene store: cosmetic faults repaired (blank
  names), structural faults dropped (blank/`all` ids, dup members,
  over-cap entries), decode never throws, rewrite only when the repaired
  form differs (load idempotent, migration repeat-safe).
- Fresh install / upgrade (keys absent): selection = All Scenes, live scene
  untouched, defaults persisted (`migrated` flag); no auto-created
  collections (custom collections are user-driven; All already shows all).
- Corrupt collection metadata degrades the VIEW, never the scene set: every
  known scene stays visible via All Scenes. Collection corruption never
  resets scenes; scene failure leaves collections alone.
- Scene deletion prunes the id from every collection (no dangling refs);
  collection deletion never deletes scenes (separate keys, tested).

## 3. Selection semantics

- Active-selection fallback chain: stored active → stored default → All.
- Active vs default: the active collection is the viewed set; the default
  is the stored fallback when the active selection is unknown.
- Collection switching changes only the viewed set — the activity performs
  no stream operation, pushes no scene update, never contacts the service;
  scene-switch semantics (`onActiveScene`, H8 live push) unchanged.

## 4. UI (existing clay/Compose architecture)

- `CollectionCard` (SceneEditorPage): All Scenes row + custom rows with
  member counts, select (Filled/Raised), inline rename of the selected
  custom collection, delete icon → confirmation dialog ("The scenes inside
  it stay in All Scenes — only the collection is removed").
- Scene list shows the active collection's view (member order); empty custom
  collections show an empty-state line.
- Membership: per-card REMOVE FROM COLLECTION while viewing a custom
  collection; `AddScenesCard` lists scenes not yet in the collection with
  ADD buttons (multi-membership supported).
- Threading: `MainActivity` state + `persistCollections()` (re-resolves
  through the pure save core so state stays canonical) → `StudioState` /
  `StudioActions` → `SceneEditorPage`. No new pages, no arch changes.

## 5. Safety (K1 strict scope)

- Untouched: RTMP, audio/mic capture, MediaProjection, GL compositor,
  rendering, transitions, video decoding, animation. `SceneConfig.kt` diff
  is 2 words (escape/unescape visibility). No service restart on collection
  switch; no scene-switch semantic change.
- Not started: K2 sources/canvas, K3 free-transform, K4 inspector, K5
  lock/groups, K6 orientations, K7 templates, K8 live push, K9 chat,
  K10 import/export.

## 6. Tests (54 new, 723/723 both harnesses)

- `scene/SceneCollectionTest.kt`: codec round-trip (incl. tabs/backslashes),
  structural repair (blank/reserved/dup ids, dup members, over-cap,
  malformed lines, unknown version), creation/caps/naming, membership
  add/remove/prune, visibility (All/null/custom/unknown/dangling/empty),
  fallback chains, load resolution (fresh/defaults, round-trip no-rewrite,
  dangling prune, unknown active/default, corrupt-degrades-view),
  save resolution, global-identity invariants (records untouched,
  shared identity, switch never changes live), v6 migration fixture.
- Negative controls: broken unknown-fallback → exactly 1 FAIL
  (`unknownCollectionFallsBackToAllScenes`); broken add-dedupe → exactly 1
  FAIL (`addSceneTwiceNeverDuplicates`); both reverts byte-verified.
- No existing test weakened, deleted, skipped, or bypassed.

## 7. Files

- NEW: `scene/SceneCollections.kt` (model + codec + rules),
  `scene/SceneCollectionStore.kt` (prefs wrapper),
  `scene/SceneCollectionTest.kt` (54 tests), `docs/K1_SCENE_COLLECTIONS.md`.
- MODIFIED: `MainActivity.kt` (state/load/handlers/persist/prune/threading),
  `AppShell.kt` (state/actions/dialog/page args), `SceneEditorPage.kt`
  (CollectionCard/AddScenesCard/filtered list/membership),
  `scene/SceneConfig.kt` (2-word visibility), `docs/ROADMAP.md` (K1 checked).
