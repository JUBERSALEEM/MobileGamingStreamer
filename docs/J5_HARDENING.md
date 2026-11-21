# J5 — Performance, Stability & Compatibility Hardening

Status: IMPLEMENTED (33 new tests; suite 669/669 on both JVM harnesses; neg control green).

Final Phase J subphase. J1–J4 reused as-is; this subphase fixes
provable defects, extracts the scheduling policy into tested pure
code, and pins budgets/isolation with regression tests. No device
was touched: the user streamed on the S25 Ultra throughout, so all
hardware behavior below is pending physical-device validation.

## Baseline findings (code-verified, no invented problems)

Solid on inspection — unchanged: zero per-frame bitmap/texture/
buffer allocation (cached buffers + handles; only tiny short-lived
sample objects during active motion); cancel-first idempotent tick
arms with zero tickers when idle; one coalesced motion redraw;
CUT never snapshots (triple-gated); pure enforced budgets (4
slots, 2 decoders deterministic, 16 MiB snapshot with Long math,
64-char text, 512 px image downsample); exactly-once teardown
ledger with restart refresh; identity-guarded player callbacks;
ST listeners nulled pre-release; `updateScene` touches no
RTMP/encoder/audio/projection; minSdk 29 covers every used API;
persistable URI permissions taken.

## Defects fixed (all minimal, all behavior-preserving)

1. Shader leak: `buildProgramFor` never freed shaders after a
   successful link — 2 GPU objects per program per scene switch.
   Now detaches + deletes post-link, exception-isolated.
2. Widget-tick realloc: `refreshTextSlotLocked` re-uploaded the
   same fixed-dim bitmap with `texImage2D` (storage realloc) —
   now `texSubImage2D`.
3. Early resume: `SceneVideoPlayer.resume()` called `start()`
   unconditionally; pre-prepare it threw and wrongly failed the
   slot. Now a no-op until prepared (prepare auto-starts).
4. Compose-failure leak: `composeImageBitmapLocked` dropped src
   from the build recycle set before OOM-risky allocs — failure
   now re-adds it (deterministic cleanup, not GC timing).
5. Retained static bitmaps: every static slot kept its CPU bitmap
   forever, but only CLOCK/TIMER ever re-read it (the refresh is
   the sole reader). Static + video-style bitmaps now recycle
   right after upload; release stays null-safe.
6. Untestable scheduling: the tick-arm decision was inline GL.
   Extracted total pure `SceneMotionPolicy` (identical semantics);
   GL arm/tick sites call it. Truth table + boundaries pinned.
7. Honesty: one `overlay build: placed/requested` summary line per
   build for skip forensics (skips already log per layer).

## Resource budgets (deterministic, code-derived worst case)

| Resource | Bound | Owner |
|---|---|---|
| Static overlay slots | ≤ 4 (`resolve` + codec caps) | GL build |
| Concurrent video decoders | ≤ 2, first-in-order (`SceneVideoBudget.assign`) | GL build |
| Transition snapshot | ≤ 16 MiB (`allowsSnapshot`, Long math) + 1×1 black | GL switch |
| Static CPU bitmaps | 0 retained (J5 recycle); dynamic glyph boxes only | GL build |
| Image decode transient | ≤ 512 px longest edge, recycled | build `finally` |
| Text glyphs | ≤ 64 chars, size = max(outH/20, 24) | codec + layout |
| OES textures / ST / Surfaces | 1 input + ≤ 2 video | GL slots |
| MediaPlayers / player threads | ≤ 2, always `quitSafely`'d | slot release |
| GL programs | ≤ 3; shaders detached post-link (J5) | GL build |
| Per-frame allocation | 0 resting/static; tiny samples only in motion | frame path |
| GL rendezvous | 5 s init/teardown/rebuild; 3 s per player stop | callers |

Video decoder byte footprint is driver-owned (codec instance + HW
buffers) — bounded by COUNT (2), not bytes. CPU bitmaps and GPU
textures are output-dimension-bounded (1080p slot ≈ 8 MB worst).

## Lifecycle & isolation

Unchanged architecture, newly pinned: start→stop→start cycles
refresh the ledger (50-cycle stress green); teardown order +
exactly-once; frame window balances (200-frame stress green);
playback matrix never stuck (50-cycle stress green); planner
rapid alternation stays IN_PLACE (50-hop chains green); service
`updateScene` structurally pinned to never touch streaming
transport (no prepare/start/stop/release of encoder, RTMP, audio,
or projection — swaps stay inside the retaining helper).

## Known limitations (documented, not changed)

- Scene switches rendezvous GL from the main thread (receiver /
  `onStartCommand`): milliseconds normally, up to 5 s worst case
  (wedged decoder stops). Async rebuild would be major surgery on
  streaming-adjacent code without device validation.
- Player stops serialize on the GL thread (worst 2×3 s vs the 5 s
  outer budget); a timeout yields a bounded false-failure status
  and the rollback recovers consistently — no leak, no reorder.
- No EGL context-loss re-init: loss surfaces as a stall and the
  existing watchdog recovery (restart) rebuilds the session.
- `checkGl` after draws stays: failure loudness (FAILED → stall
  detection) outweighs the per-call cost by design.

## Compatibility (minSdk 29, verified by API level)

`loadThumbnail` (29, exact), `getScaledFrameAtTime` (27),
`EGLExt` (18), EGL14/GLES20 ES2 shaders, `quitSafely` (18),
`OpenDocument` pickers (AndroidX), notification/mic/projection
permission paths (guarded ≥ 33 / runtime). Portrait + landscape
covered by layout/wipe/slide tests both orientations. No
unconditional above-min API usage; lint green in CI.

## Pending physical-device validation (user streaming)

Wall-clock frame timing, decoder behavior under load, MMR/
MediaPlayer content-URI behavior across providers, EGL
context-loss recovery end-to-end, memory pressure, and the J4
snapshot-orientation gate all require the S25 Ultra after the
stream. Nothing here claims hardware measurement.
