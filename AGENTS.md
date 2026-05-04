# GxCloud — Agent Reference

This document captures every non-obvious decision made in this codebase, why it was made, and what was tried and rejected. Read this before suggesting changes.

---

## Device

**Logitech G Cloud** — Snapdragon 720G, Adreno 618, Android, API 26+, targetSdk 36.  
All empirical findings below are specific to this device/WebView combination unless noted otherwise.

---

## Video Pipeline — The Core Decision

### Why bridge canvas instead of VideoFrame

`new VideoFrame(video)` + `gl.texSubImage2D` was tried and confirmed working (no black screen, no choppiness) but measured **worse battery** than the bridge canvas approach.

Root cause (Opus analysis): On Android WebView / Adreno 618, VideoFrame→texSubImage2D does not stay GPU-resident. The source is a `GL_TEXTURE_EXTERNAL_OES` buffer (gralloc NV12 from MediaCodec). Converting that to a standard WebGL `sampler2D` requires an extra external-OES→2D resolve pass, and texSubImage2D on Android Chromium often falls into a CPU staging path when source colorspace/format doesn't match. This extends the GPU's busy window and pushes Adreno DCVS up a power level.

The bridge canvas path (`drawImage` → `texImage2D`) is counterintuitively better because:
- Canvas 2D `drawImage` has been heavily optimised in Chromium for years and stays GPU-resident end to end
- `texImage2D` with a canvas source allows Chromium to swap SharedImages (handle swap, no copy)
- `texSubImage2D` forces a write into existing storage, bypassing the SharedImage mechanism

**Do not switch to VideoFrame + texSubImage2D. Do not switch canvas + texSubImage2D either — texImage2D is the correct call for the bridge path.**

### Why texImage2D not texSubImage2D for the bridge canvas

Even with the bridge canvas, `texImage2D` is preferred over `texStorage2D` + `texSubImage2D`. The `texImage2D` path lets Chromium swap SharedImages; `texSubImage2D` forces a write into existing storage and takes a slower path on this device's WebView stack.

---

## Render Loop — rAF + rVFC Hybrid

### Current design

- **rAF** schedules the render callback (vsync-aligned, 60Hz)
- **rVFC** (`requestVideoFrameCallback`) sets a `newFrame` flag when the decoder produces a frame
- `drawArrays` only runs when `newFrame` is true — shader runs exactly once per decoded frame

### Why not pure rVFC

Pure rVFC (rVFC drives the render, no rAF) was implemented and caused a **GPU regression** — higher power draw on device. Likely cause: rVFC fires when the decoder produces a frame, which is not vsync-aligned. On Adreno's TBR architecture, GPU work is most efficient when aligned to vsync (one GPU wakeup handles both shader work and compositor work). rVFC firing outside vsync causes two GPU wakeups per frame instead of one.

**Keep rAF as the scheduler. rVFC is the signal only.**

### Why drawArrays is inside the newFrame check

`preserveDrawingBuffer: false` + `desynchronized: true` means the OS compositor re-presents the last submitted surface on ticks where no new draw occurs. No need to redraw on duplicate frames. Moving drawArrays inside the newFrame check eliminates shader invocations on rAF ticks where the video hasn't produced a new frame (e.g. 30fps content at 60Hz display).

---

## WebGL Context Flags

```js
{ powerPreference: 'low-power', alpha: false, depth: false, stencil: false,
  preserveDrawingBuffer: false, antialias: false, desynchronized: true, premultipliedAlpha: false }
```

- **`powerPreference: 'low-power'`** — picks lower-power GPU configuration on Adreno
- **`alpha: false`** — no alpha channel needed, saves memory bandwidth
- **`depth: false`, `stencil: false`** — no buffers allocated, saves memory; `gl.disable(DEPTH_TEST/STENCIL_TEST)` are therefore no-ops
- **`preserveDrawingBuffer: false`** — enables swap-chain optimisation; compositor holds last frame, no extra copy
- **`antialias: false`** — no MSAA buffer; `gl.disable(SAMPLE_COVERAGE)` is therefore a no-op
- **`desynchronized: true`** — deliberate latency tradeoff, decouples WebGL swap from compositor. **Do not remove.**
- **`premultipliedAlpha: false`** — consistent with `alpha: false`

---

## GL State

- **`gl.disable(BLEND)`** — output is opaque, no blending needed
- **`gl.disable(DITHER)`** — some drivers re-enable dither; explicitly off
- **`gl.hint(GENERATE_MIPMAP_HINT, FASTEST)`** — mipmaps are never generated (NEAREST filter, no mipmap levels), this is a safety guard
- **`gl.disable(DEPTH_TEST)`, `gl.disable(STENCIL_TEST)`** — no-ops (buffers don't exist), do not add
- **`gl.disable(SCISSOR_TEST)`, `gl.disable(CULL_FACE)`** — off by default, do not add
- **`gl.disable(SAMPLE_COVERAGE)`** — no-op (antialias: false), do not add

---

## Texture

- **NEAREST filtering** — correct. The shader does its own 5-tap sampling with explicit offsets. Bilinear would blur the input before the sharpener sees it. Canvas output is sized to native video resolution so there is no fractional scaling within the shader.
- **CLAMP_TO_EDGE** — prevents edge artifacts from the neighbor taps at frame borders
- **1×1 black init** (`EMPTY_PIXEL`) — initialises texture to valid state before first rVFC fires, preventing undefined samples if drawArrays were ever called early
- **`UNPACK_FLIP_Y_WEBGL = false`** — avoids CPU-side flip in the upload path; UV flip handled in vertex shader
- **`UNPACK_ALIGNMENT`** — irrelevant for canvas sources, driver ignores it
- **`gl.texImage2D` every frame** — see bridge canvas section above. Do not change to texSubImage2D.

---

## Shader

### Vertex shader

Neighbor UVs (vUVb, vUVd, vUVf, vUVh) are precomputed in the vertex shader and passed as varyings. `texelSize` uniform lives in the vertex shader. With the oversized triangle trick, vertex shader runs 3 times per frame; the 4 UV additions run 3 times instead of ~2M times.

`texelSize` is set once at init and updated only on resolution change (inside the canvas size guard). It is **not** set per frame.

### Fragment shader

- **`precision mediump float`** — Adreno 618 runs mediump on FP16 ALUs (~2× throughput vs highp). Do not change to highp.
- **`const vec3 lw`** — compile-time constant enables driver constant folding on all `dot(x, lw)` calls. Do not make it a uniform.
- **`const float sharpenFactor`** — compile-time constant, no uniform lookup needed
- **No `sqrt` in `amp`** — removed. `amp = mn_l / (mx_l + 0.01)` is the simplified form, already optimal (3 GPU ops: add, RCP, mul)
- **Saturation boost** (`wm * 0.16`) — intentional, user wants this visual style. Do not remove.
- **`mix(vec3(le), s, satBoost)`** — maps to a single hardware LRP instruction. Do not replace with expanded form (`le + (s-le)*satBoost`) which is 3 instructions.
- **5-tap cross pattern** — minimum for isotropic sharpening. Dropping to 4 taps requires an asymmetric or diagonal pattern with visible quality loss on game content. Do not reduce.

### Things that don't help the shader

- `fma()` — compiler already generates FMA for `a*b+c` patterns
- `inversesqrt` for amp — different formula AND slower (4 ops vs 3)
- Moving drawArrays outside newFrame — wastes shader invocations on duplicate frames
- `gl.flush()` after draw — fights against `desynchronized: true`, prevents command batching

---

## Bridge Canvas

```js
bridge.getContext('2d', { alpha: false, willReadFrequently: false })
bridgeCtx.imageSmoothingEnabled = false;
bridgeCtx.globalCompositeOperation = 'copy';
```

- **`alpha: false`** — opaque canvas, no alpha compositing
- **`willReadFrequently: false`** — we never call `getImageData`, so no CPU-readback hint needed
- **`imageSmoothingEnabled = false`** — no interpolation during drawImage
- **`globalCompositeOperation = 'copy'`** — skips alpha blend, overwrites pixels directly (default 'source-over' blends unnecessarily)
- **`desynchronized: true` on bridge** — irrelevant, bridge canvas is off-screen and never composited. Do not add.
- Bridge canvas lives at IIFE scope (not inside `setupWebGLCAS`) — reused across context loss/restore events without reallocating

---

## Android / Kotlin

- **`preferMinimalPostProcessing = true`** — disables SurfaceFlinger HDR tone-mapping and display post-processing. Real battery saving.
- **`preferredDisplayModeId` → 60Hz** — prevents display running at higher refresh rates for a 60fps stream
- **`LAYER_TYPE_NONE`** — WebView default. Do not change to `LAYER_TYPE_HARDWARE` (adds an extra off-screen compositing texture wrapping a WebView that already does its own GPU rendering)
- **`setBackgroundDrawable(null)` + black WebView bg** — eliminates window background overdraw
- **`importantForAutofill = NO`, `importantForAccessibility = NO`** — prevents autofill/accessibility scans on focus, saves CPU
- **`isHapticFeedbackEnabled = false`** — prevents haptic driver calls from touch events
- **`isLongClickable = false`** — suppresses context menu; `setOnLongClickListener { true }` is redundant if this is set
- **`isSaveEnabled = false`** — no WebView state worth restoring; prevents serialization on activity recreation
- **`setOffscreenPreRaster(false)`** — prevents pre-rasterization of offscreen content
- **`pauseTimers()` + `RENDERER_PRIORITY_WAIVED` on pause** — reduces renderer process priority and stops JS timers when backgrounded
- **`systemUiVisibility` flags** — confirmed hardware compatibility requirement on this device. Do not replace with `WindowInsetsControllerCompat`. This is a known incompatibility.
- **`AUDIOFOCUS_GAIN`** — correct for a multi-hour gaming session. `AUDIOFOCUS_GAIN_TRANSIENT` is for brief interruptions and would allow other apps to resume audio mid-game.
- **`largeHeap` removed** — WebView runs in a separate renderer process; largeHeap only affects the tiny main process. No benefit.
- **`forceDark = FORCE_DARK_OFF` / `setAlgorithmicDarkeningAllowed(false)`** — prevents WebView from applying color inversion on top of Xbox's already-dark UI. Likely no measurable battery saving since the site is already dark, but correct.

---

## CSS / JS

- **`canvas.style.contain = 'strict'`** — tells browser this canvas is independent from page layout; prevents layout/paint recalculation from propagating through/to the canvas
- **`overscrollBehavior = 'none'`** — prevents pull-to-refresh and overscroll effects
- WebGL canvas is already on its own GPU compositor layer by definition. `translateZ(0)` and `will-change: transform` are no-ops on WebGL canvases.
- `video.style.willChange = 'transform'` — pointless on a hidden video, would just waste a compositor layer

---

## What Was Measured and Confirmed

| Change | Result |
|--------|--------|
| VideoFrame + texSubImage2D | Smooth but higher battery than bridge — rejected |
| Bridge canvas + texImage2D | Best battery — current approach |
| Pure rVFC (no rAF) | GPU regression, higher power — rejected |
| rAF + rVFC hybrid | Current approach, confirmed better |
| NEAREST vs LINEAR filtering | NEAREST confirmed no quality loss at native res, less texture unit work |
| preferMinimalPostProcessing | Real battery saving |
| 60Hz display pin | Real battery saving |

---

## Manifest

- **`android:screenOrientation="landscape"`** — gaming handheld, always landscape
- **`android:configChanges`** — covers orientation/screen/keyboard to prevent Activity recreation
- **`android:launchMode="singleTask"`** — single instance
- **`android:hardwareAccelerated="true"`** — default for API 14+, explicit for clarity
- **`android:largeHeap` removed** — WebView is a separate process, largeHeap had no effect
