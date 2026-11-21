# H9 — Clock & Timer Widgets

Status: IMPLEMENTED (541 JVM tests green, CI on exact HEAD).

Implementation notes (review amendments, all within scope):
- Ticks skip unchanged slots (per-slot last content), so a
  CLOCK-only stack re-uploads once a minute even though the
  single ticker fires on the fastest needed cadence.
- Failed slot refreshes log loudly and retry next tick — a
  wedged tick never kills the GL thread (unlike init, which
  must fail loudly into the start-failure path).
- TIMER past 100h reads a steady 99:59:59 (capped, documented
  in the pure contract — never wraps to zero on a live stream).

## 0. Goals / non-goals

- CLOCK layers: wall-clock HH:MM (24h, ASCII digits, device zone),
  re-rendered on minute boundaries. TIMER layers: session-uptime
  HH:MM:SS, ticking every second. Both are first-class layers:
  create/edit/delete/reorder/anchor/style/opacity/visibility,
  counted toward the existing 4-layer cap (no extension).
- Renderer and preview share one pure time contract; preview ticks
  honestly (real wall time; uptime demo counts from preview start,
  labeled PREVIEW as always).
- Non-goals: countdown with transport controls, seconds on the
  clock, 12-hour locale formats, animated transitions — and the
  browser source, which is evaluated and EXCLUDED in §7 (never fake).

## 1. Model + validation + codec (no version bump)

- `SceneLayerKind` gains CLOCK and TIMER. `of()` accepts them with
  no text and no URI (both forced to empty/null, like TEXT forces a
  null URI); TEXT/IMAGE rules byte-identical.
- Codec stays v3: kinds serialize by name, and `decodeLayer`
  already drops unknown kinds (unknown `valueOf` → layer dropped,
  scene kept). A new-kind layer in an old app drops cleanly; old
  scenes decode identically here. Pinned by an unknown-kind test.
- `streamableSpecs`/`activeSceneEncoding` include widgets: a
  clock-only scene activates the scene source. Widgets never need a
  fallback: they cannot fail to load (no bytes, no URIs).

## 2. Pure time contract (`SceneClockWidgets`)

- `WALL_CLOCK_WORST_CASE = "88:88"`, `TIMER_WORST_CASE =
  "88:88:88"`: fixed-box measure strings shared by GL and preview.
  Boxes never resize, so placement is stable and re-render is a
  same-size texture re-upload (`8` is the widest digit in
  proportional fonts; padding absorbs the rest).
- `formatWallClock(epochMs, zone): String` — HH:MM via `java.time`
  (minSdk 29, JVM-safe), manual zero-pad (locale-independent ASCII).
- `formatElapsed(startMs, nowMs): String` — HH:MM:SS from monotonic
  millis, negatives clamp to zero, hours cap at 99.
- `tickDelayMs(nowMs, periodMs): Long` — millis until the next
  boundary, always in `(0, period]` (exact-boundary ticks wait a
  full period; a zero delay could busy-loop).

## 3. Renderer: dynamic GL slots

- `OverlaySlot` gains its spec, text offsets, and last rendered
  content. Build measures widgets with the worst-case strings
  (same Paint path as text) but rasters the CURRENT value into the
  initial bitmap.
- One GL-thread ticker (single delayed runnable, no new thread):
  re-renders each dynamic slot in place (erase + same style order +
  same offsets, `texImage2D` re-upload into the live texture, zero
  realloc), skipping slots whose content is unchanged. Cadence is
  the fastest needed (TIMER 1s, else CLOCK 60s), scheduled to the
  next boundary. No frame can interleave: ticker and frames share
  the GL handler queue.
- Lifecycle: slot release cancels the ticker; build re-arms it, so
  init, H8 rebuilds (and their rollbacks), and teardown all keep
  exactly one ticker iff a complete stack holds a dynamic slot.
- TIMER epoch: the service session start (monotonic ms) threads
  through `SceneSource` into GL, so swaps preserve true session
  uptime. Absent (legacy ADB path), the source latches its own
  start — counting from the first frame is the best available
  truth there, and is documented, not faked.

## 4. Preview (honest tick)

- Same worst-case measure + same H4 `resolve`; a 1-second recompose
  tick runs ONLY when the scene holds a dynamic layer. CLOCK shows
  the real wall time (same minute the stream shows); TIMER counts
  from preview start as a live demo. Ghost/disabled, style, and
  warning behavior unchanged.

## 5. Editor + activity

- ADD CLOCK / ADD TIMER beside ADD TEXT / ADD IMAGE (same cap
  error); rows titled "Clock widget" / "Stream timer" with an info
  line instead of a text field (nothing to type) or thumbnail;
  shared position/opacity/style controls, with the text-color
  swatch applying to digits (any non-IMAGE kind).
- No USE/push change: widgets ride the H5 persist + H8 live-switch
  paths unmodified (in-place rebuild re-arms the ticker).

## 6. Compatibility

- H2–H8 untouched behaviorally: static layers render identically
  (same measure/raster/upload code, same draw loop); the ticker
  exists only with dynamic slots. No new permissions/dependencies;
  `java.time` needs no desugaring on minSdk 29.

## 7. Browser source: evaluated, EXCLUDED

- No honest GPU texture path exists: WebView renders into its own
  view hierarchy, and the only extraction is main-thread bitmap
  polling (`TextureView.getBitmap` per frame — jank, battery, and
  Play-policy-sensitive capture of third-party content) or
  long-deprecated capture APIs. Shipping that as a "browser
  source" would be fake, so it is not shipped. Unknown codec kinds
  drop by design, so no future/experimental kind can render as
  anything but nothing. A future honest browser needs a dedicated
  producer surface — out of scope, tracked here, not faked.

## 8. Tests

- `SceneClockWidgetsTest` (pure): wall-clock vectors incl. zone
  offset, elapsed boundaries/negatives/cap, tick-delay boundaries
  (exact-boundary → full period, never zero), worst-case pins.
- Codec: widget round-trip, unknown-kind (BROWSER) drops the layer
  but keeps the scene/siblings, widget `of()` forcing.
- Structure pins: GL ticker present + release-cancel + shared
  formatter use on both sides; service threads the session start;
  activity/editor add actions; preview ticks.
- Full gates: 526+ JVM suite, K222, CI on exact HEAD. S25: clock
  minute correctness, timer cadence, timer survival across
  in-place + swap switches, preview tick (PENDING per standing
  rule until hardware evidence).

## 9. Build order

1. Kinds + `of()`/codec + `SceneClockWidgets` + pure/codec tests.
2. GL dynamic slots + ticker.
3. SceneSource session-start threading.
4. Service session start + pass-through (initial + swap).
5. Editor UI + preview tick + AppShell + activity add fns.
6. Tests + harness + gates + commit + push + CI.
