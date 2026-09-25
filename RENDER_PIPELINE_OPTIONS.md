# Render pipeline alternatives for the G Cloud (Adreno 618, Android 11)

## Context

The app streams xCloud inside a WebView and applies a CAS sharpener by copying the stream
`<video>` through a 2D bridge canvas into a WebGL2 texture every frame. Battery drain is high
and the AGENTS.md benchmarks are stale, so the user wants a fresh look at pipeline options.
Hard constraints from the user: **ignore existing benchmarks; only options that improve quality
and latency while preserving battery; in-page WebGL scope only (no native client for now).**

### Device facts that decide what is possible

| Fact | Consequence |
|------|-------------|
| G Cloud runs Android 11 (API 30); Logitech release notes through June 2026 show no OS bump | `View.setRenderEffect` + AGSL `RuntimeShader` (API 33) is **out**. WebGPU is Android 12+ in Chrome and is not shipped in Android WebView at all, so `importExternalTexture` (the true zero-copy path) is **out**. |
| 1920×1080 60 Hz panel; stream is typically 1080p | Every full-resolution pass is ~2 MPix × 60 Hz. Pass count is the battery lever. |
| Chrome's `MediaStreamTrackProcessor` is main-thread only | A worker pipeline needs `postMessage` frame transfer; main-thread version comes first. |

### Measured on device 2026-09-02 (supersedes assumptions above)

| Fact | Value | Consequence |
|------|-------|-------------|
| WebView Chromium | **151.0.7922.200** | The device is Android 11 but the **WebView is modern**. Any option blocked purely by "old WebView" must be re-tested, not assumed. `MediaStreamTrackProcessor` is comfortably available. |
| Decoder | **hardware, power-efficient** (`mediaCapabilities` `type:'webrtc'` → `powerEfficient: true`) | Decode is **not** the drain. Suspect cleared. |
| Decode latency | `dec 11.53ms`, `proc 12.52ms` | Async hardware pipeline latency, not CPU. Not fixable in-page. |
| Jitter buffer | `jb 0.69ms` | Already minimal. Do not touch. |

Remaining blockers are **policy and platform**, not WebView age: WebGPU is still not shipped in
Android WebView by default (option 7), and `RenderEffect` needs Android 13 (option 6). A
capability line was added to the overlay to feature-detect `navigator.gpu` on-device rather than
rely on that assumption.

### Where the current pipeline spends GPU passes (per frame, all at 1080p)

1. Hidden `<video>` still decodes (needed either way).
2. `bridgeCtx.drawImage(video)` — external-OES → RGBA copy #1 (Skia).
3. `gl.texImage2D(bridge)` — RGBA → RGBA copy #2.
4. CAS `drawArrays` — copy #3, the only one doing useful work.
5. Chromium compositor draws the canvas quad; HWUI draws the WebView; SurfaceFlinger composites.

Plus a JS scheduling hop: decoder → element frame queue → `requestVideoFrameCallback` → draw.
Copies #2 and #3 and the rVFC hop are the removable parts within in-page scope.

## Options considered (ranked)

| # | Pipeline | Quality | Latency | Battery (theory, unmeasured) | Status |
|---|----------|---------|---------|------------------------------|--------|
| 1 | **`MediaStreamTrackProcessor` → `VideoFrame` → `texImage2D(frame)` → CAS** (main thread) | Identical (same CAS, same RGBA input) | Better: frames arrive straight off the WebRTC track, no element frame queue, no rVFC wait | One fewer full-res copy than today | **Experiment 1** |
| 2 | Option 1 + swap `video.srcObject` to an audio-only `MediaStream` so the element stops sinking video frames | Identical | Same as 1 | Removes the element's own per-frame sink work | **Experiment 2** (only if 1 wins) |
| 3 | Option 1 with frames transferred to a Worker + `OffscreenCanvas` WebGL2 | Identical | Draw no longer blocked by xCloud's React main-thread work; one extra postMessage hop | Neutral | **Experiment 3** (only if 1 wins and jank is observed) |
| 4 | Direct `gl.texImage2D(video)` (no bridge, no MSTP) | Identical | Slightly better (drops copy #1 latency) | Previously measured worse; kept only as a control if 1 regresses | Control |
| 5 | CSS/SVG `feConvolveMatrix` on the video | Worse (fixed kernel, halos) | — | — | Rejected on quality |
| 6 | HWUI `RenderEffect` RuntimeShader on the WebView | Identical, applies to UI too | Best in-page | Best in-page (1 extra pass total) | Blocked: needs Android 13 |
| 7 | WebGPU `importExternalTexture` | Identical | Best | Best (zero copy) | Blocked: WebView lacks WebGPU, needs Android 12+ |
| 8 | Native WebRTC + MediaCodec → external-OES GLES CAS → SurfaceView | Identical or better (CAS on decoded YUV, HW overlay) | Best overall | Best overall (no Chromium compositor) | Out of scope per user; note in AGENTS.md as the ceiling |

Per the one-change-at-a-time rule, only Experiment 1 gets implemented now. 2 and 3 are
described so the branch points are clear, and each is gated on the previous measurement.

## Experiment 1: MSTP frame pipeline (replace bridge + rVFC)

All changes are inside `INJECT_SCRIPT` in
`app/src/main/java/com/example/gxcloud/MainActivity.kt`, within `setupWebGLCAS`. Everything
outside the draw path is untouched: acquisition (`isStreamVideo`, `foundVideo`, `bindWhenSized`,
play() patch), `teardown`/`detach`/`_casCleanup`, the always-spinning rAF liveness ticker (keep
it, it is the warm-loop control), watchdog, context-loss handling, canvas placement on body,
shaders, GL state, texture params.

### Design

- **Feature detect** at IIFE scope: `hasMSTP = 'MediaStreamTrackProcessor' in window`. If
  false, the existing bridge + rVFC path runs unchanged (it becomes the fallback, alongside the
  existing `!hasRVFC` fallback).
- **Source**: in `setupWebGLCAS`, when `hasMSTP && video.srcObject`, take
  `video.srcObject.getVideoTracks()[0]`, build `new MediaStreamTrackProcessor({ track })`, and
  pump `processor.readable.getReader()` in an async loop:
  1. `const { value: frame, done } = await reader.read()`
  2. if paused/hidden/not-alive: `frame.close()` and continue (never hold a frame; backpressure
     stalls the decoder).
  3. `gl.texImage2D(TEXTURE_2D, 0, RGBA, RGBA, UNSIGNED_BYTE, frame)` then `drawArrays`, then
     `frame.close()` in a `finally`.
  4. Keep `texImage2D` (not `texSubImage2D`) so the orphaning behaviour matches today's path;
     the only variable changed is the source.
- **Sizing**: `_syncSize` gains a frame-driven path: compare `frame.displayWidth/Height` to
  `canvas.width/height` on each frame (cheap integer compare) and resize the canvas + viewport
  on change. This replaces the `resize`/`loadedmetadata` listeners for the MSTP path and keeps
  the "no debounce, equality early-return" behaviour. The bridge canvas is not touched in this
  path (stays 1×1 / unused).
- **Lifecycle**: `cancelFrame` additionally calls `reader.cancel()` and `stopReader()`;
  `scheduleFrame` restarts the pump. `detach` cancels the reader and closes any in-flight frame.
  Track `ended` event on the MediaStreamTrack also routes to `teardown(video)`.
- **`present()` gating** stays identical: `video.readyState >= 2 && !video.paused && !document.hidden`,
  evaluated per frame before upload, otherwise `frame.close()`.
- **rVFC** is not armed when the MSTP pump is active (`hasRVFC && !mstpActive`). The rAF loop
  still spins unconditionally as today.
- **Latency probe** (temporary, behind a `window.__gxcloudLatencyProbe` flag, removed after
  measurement): record `performance.now() - (frame.timestamp / 1000 + performance.timeOrigin)`
  is not meaningful for WebRTC clocks, so instead log the delta between consecutive
  `reader.read()` resolutions and the time from read-resolve to `drawArrays` return, and compare
  against the same probe on the rVFC path (rVFC `metadata.expectedDisplayTime - now`). Console
  output only, sampled every 300 frames.

### Files

- `app/src/main/java/com/example/gxcloud/MainActivity.kt` — `setupWebGLCAS` inside `INJECT_SCRIPT` (lines ~634–872).
- `AGENTS.md` — replace the stale "What Was Measured" table with a dated one; add a "Blocked by
  Android 11" section for options 6/7 and note option 8 as the theoretical ceiling.

## Experiment 2 (gated): stop the `<video>` from sinking frames

After Experiment 1 measures ≥ baseline: in `setupWebGLCAS`, set
`video.srcObject = new MediaStream(original.getAudioTracks())` while keeping a reference to the
original stream for the MSTP reader. Risk: xCloud reads `videoWidth`/`videoHeight` or track
stats off the element; if the stream UI breaks (resolution badge, stats overlay), revert. Restore
the original `srcObject` in `detach`.

## Experiment 3 (gated): Worker + OffscreenCanvas

Only if Experiment 1 wins and frame pacing shows main-thread stalls. `canvas.transferControlToOffscreen()`,
WebGL2 in a dedicated worker, main-thread MSTP loop does `worker.postMessage(frame, [frame])`.
Feature-detect `getContextAttributes().desynchronized` on the OffscreenCanvas context; if it
reports false, this experiment is dropped (desynchronized is load-bearing per AGENTS.md).

## Verification

1. **Build**: `./gradlew assembleDebug` compiles; install on the G Cloud via adb.
2. **Function**: stream starts, CAS visibly active (compare a paused frame with the video
   unhidden vs hidden), resolution switch mid-stream re-sizes with no stretched frame, quitting a
   game tears down the canvas (no frozen frame), reopening a game rebinds, guide open/close
   leaves CAS running. Run with chrome://inspect on the WebView to confirm no per-frame console
   errors and that `MediaStreamTrackProcessor` was actually taken (log once at setup).
3. **Quality**: pixel-identical by construction (same shader, same RGBA input). Spot check one
   scene side by side with the old build.
4. **Battery**: same protocol as previous rows: same game, same brightness, 15 min, note %
   drop; run old APK and new APK back to back, twice each. Record in AGENTS.md with date.
5. **Latency**: console probe above, plus subjective input-to-photon check on a fast-twitch
   title. Record read-to-draw median and p95 for both paths.
6. Only after a clear result, delete the probe and decide whether Experiment 2 runs.

## Up-front cost statement (per the one-change-at-a-time rule)

- **Quality**: unchanged (identical shader and input format).
- **Network**: unchanged (no bitrate/codec changes).
- **Battery**: expected better (one fewer 1080p copy per frame); risk that VideoFrame→texImage2D
  takes a CPU staging path on this driver, in which case it will measure worse and is reverted.
- **Latency**: expected better (no element frame queue, no rVFC vsync wait).
