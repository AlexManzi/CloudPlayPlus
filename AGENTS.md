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

`bridge canvas + texSubImage2D` was tested and measured **higher battery** than `texImage2D`. Two mechanisms at play:

1. **Texture orphaning:** `texImage2D` implicitly orphans the old backing store — if the GPU is still reading the texture from the previous `drawArrays`, the driver hands a fresh buffer immediately with no stall. `texSubImage2D` updates in place and may require a GPU pipeline barrier (sync stall) before the write can proceed. On Adreno 618's ANGLE/GLES2 driver, this shows up as increased power.

2. **SharedImage path:** `texImage2D` with a canvas source allows Chromium to swap SharedImages (handle swap, no pixel copy). `texSubImage2D` forces a write into existing storage, bypassing this mechanism. Note: this advantage disappears if the source were `video` directly — the bridge canvas already breaks any zero-copy path. Both calls receive CPU pixels by the time they run; the orphaning difference is what matters here.

**Do not switch to `texSubImage2D` for the bridge canvas. Do not pre-allocate with `texStorage2D`. `texImage2D` every frame is correct.**

---

## Render Loop — rAF + rVFC Hybrid

### Current design

- **rAF** schedules the render callback (vsync-aligned, 60Hz)
- **rVFC** (`requestVideoFrameCallback`) sets a `newFrame` flag when the decoder produces a frame
- `drawArrays` only runs when `newFrame` is true — shader runs exactly once per decoded frame

### Why not pure rVFC

Two variants were tried and both rejected:

**Variant A — pure rVFC, no rAF:** rVFC fires → schedules a one-shot RAF → RAF draws. No continuous loop. Measured **~60% battery increase** (~5% per 15 min vs ~3.25% baseline). Likely cause: rVFC fires when the decoder produces a frame, which is not vsync-aligned. On Adreno's TBR architecture, GPU work is most efficient when aligned to vsync — one GPU wakeup handles both shader work and compositor work. rVFC firing off-vsync causes two GPU wakeups per frame instead of one.

**Variant B — rVFC as sole trigger (no rAF loop at all):** Same failure mode as Variant A.

**Keep rAF as the scheduler. rVFC is the dirty-frame signal only.**

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
- ~~`gl.hint(GENERATE_MIPMAP_HINT, FASTEST)`~~ — **removed.** Confirmed no-op: no mipmaps are generated, NEAREST filter never triggers the mipmap path. Do not re-add.
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

Vertex shader only computes `vUV` from `position`. No `texelSize` uniform, no neighbor UV varyings.

Previously, neighbor UVs (vUVb/d/f/h) were computed in the vertex shader to shift 4 additions from per-pixel to 3 per frame. That required a `texelSize` uniform updated on every resize. Replaced with `textureOffset` in the fragment shader — the GLSL compiler folds constant `ivec2` offsets into the sampler at compile time (zero runtime ALU cost), so the per-pixel arithmetic difference is gone. Eliminates 4 varyings and the uniform, reducing interpolator bandwidth and the resize codepath.

**Do not reintroduce `texelSize` or neighbor UV varyings.**

### Fragment shader

- **`textureOffset(data, vUV, ivec2(...))`** — constant-offset variant; GLSL ES 3.0 core. Compiler folds the offset into the texture fetch. Equivalent cost to a plain `texture()` call. Offsets ±1 are well within `gl_MinProgramTexelOffset`/`gl_MaxProgramTexelOffset` range.
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

## Discord Overlay

### State machine
Two states: `CLOSED ↔ UI_VISIBLE`. 4 rapid taps (within 600ms) anywhere on screen opens Discord when `discordEnabled` is true; 4 more taps closes it.

### Toggle gates everything
`discordEnabled` (set via `AndroidBridge.setDiscordEnabled()` from the injected JS toggle) is the single gate. When false, the early-return in `dispatchTouchEvent` fires immediately — no Discord WebView is loaded, no network activity, no battery cost. Do not add secondary guards.

### Toggle placement
A custom `<div>` is injected into xCloud's `#jump-panel` → `section[data-auto-focus="true"]` via `INJECT_SCRIPT`. A `reinjector` MutationObserver watches that section and re-injects the toggle if React removes it. The toggle state is tracked in JS (`discordEnabledJS`) and synced to Kotlin via `AndroidBridge.setDiscordEnabled()`.

### Microphone permission
Requested on the first `openDiscord()` call, not at startup. Discord defaults to disabled so requesting at startup would prompt before the user opts in.

**Known issue:** `openDiscord()` calls `requestPermissions` then immediately calls `loadUrl` without waiting for the result. There is no `onRequestPermissionsResult` override. On first use, Discord loads before mic is granted — voice is broken until app restart. Fix before release.

### Discord WebView optimisations
- **`RENDERER_PRIORITY_IMPORTANT`** when UI is visible — ensures Discord renders correctly
- **`RENDERER_PRIORITY_WAIVED`** on close — deprioritises renderer process when Discord is not visible
- **`about:blank` on close** — releases Discord's network connections and DOM memory on full close

### Known races
- **Close→open freeze:** `onPageFinished("about:blank")` calls `view.onPause()`. A fast close→reopen can race with `onResume()` from `openDiscord()` and leave the WebView frozen. Guard by checking `discordState` inside `onPageFinished` before calling `onPause()`.

### Jump panel observer scope
`watchForJumpPanelRemoval` previously observed `document.body { childList, subtree:true }` — caused sustained CPU drain during streaming by firing on every React DOM mutation. Fixed to observe `panel.parentNode { childList:true }` only. **Do not widen this back to document.body.**

### Why a separate WebView for Discord
Discord Web requires `domStorage`, media permissions, and a desktop user-agent. Sharing the main WebView with Xbox would require constant URL switching and lose game session state. A separate WebView lets both run independently.

---

## CSS / JS

- **`canvas.style.contain = 'strict'`** — tells browser this canvas is independent from page layout; prevents layout/paint recalculation from propagating through/to the canvas
- **`overscrollBehavior = 'none'`** — prevents pull-to-refresh and overscroll effects
- WebGL canvas is already on its own GPU compositor layer by definition. `translateZ(0)` and `will-change: transform` are no-ops on WebGL canvases.
- `video.style.willChange = 'transform'` — pointless on a hidden video, would just waste a compositor layer
- **Injected CSS uses universal `*` selector** — `* { -webkit-tap-highlight-color: transparent !important; outline: none !important; }` must target all elements. Narrowing to `video,canvas` causes the Discord toggle to stop appearing in the Xbox guide panel (exact mechanism unclear, likely xCloud's guide CSS interacting with missing `outline: none` on section/container elements). **Do not narrow this selector.**

---

## What Was Measured and Confirmed

| Change | Result |
|--------|--------|
| VideoFrame + texSubImage2D | Smooth but higher battery than bridge — rejected |
| Bridge canvas + texImage2D | Best battery — current approach |
| Bridge canvas + texSubImage2D | Higher battery than texImage2D (pipeline barrier stalls) — rejected |
| Pure rVFC drives RAF (no continuous loop) | ~60% battery increase vs baseline — rejected |
| rAF + rVFC hybrid | Current approach, confirmed better |
| `document.body { subtree:true }` for jump panel removal | Sustained CPU drain during streaming — narrowed to `panel.parentNode` |
| NEAREST vs LINEAR filtering | NEAREST confirmed no quality loss at native res, less texture unit work |
| `textureOffset` vs neighbor UV varyings | Same visual output, fewer varyings, no texelSize uniform — current approach |
| preferMinimalPostProcessing | Real battery saving |
| 60Hz display pin | Real battery saving |

---

## Manifest

- **`android:screenOrientation="landscape"`** — gaming handheld, always landscape
- **`android:configChanges`** — covers orientation/screen/keyboard to prevent Activity recreation
- **`android:launchMode="singleTask"`** — single instance
- **`android:hardwareAccelerated="true"`** — default for API 14+, explicit for clarity
- **`android:largeHeap` removed** — WebView is a separate process, largeHeap had no effect
