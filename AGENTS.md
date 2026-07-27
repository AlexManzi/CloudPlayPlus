# GxCloud — Agent Reference

This document captures every non-obvious decision made in this codebase, why it was made, and what
was tried and rejected. Read this before suggesting changes.

---

## Device

**Logitech G Cloud** — Snapdragon 720G, Adreno 618, Android, API 26+, targetSdk 36.
All empirical findings below are specific to this device/WebView combination unless noted otherwise.

---

## Current State at a Glance

The entire app is one file: `app/src/main/java/com/example/gxcloud/MainActivity.kt` (~970 lines).
Roughly the first 540 lines are Kotlin; the rest is `INJECT_SCRIPT`, a JS blob evaluated into the
main WebView on every `onPageFinished`.

- **Main WebView** loads `https://play.xbox.com/` with a Windows/Edge desktop user agent.
- **CAS shader**: `INJECT_SCRIPT` finds the real stream `<video>`, hides it, and draws a sharpened
  copy to a fixed-position WebGL2 canvas layered over the page.
- **Discord** and **Notes** are lazily-inflated `ViewStub`s — zero cost until first opened.
- **Guide injection**: a Notes item, a Discord toggle, and a battery/clock overlay are injected
  into the xCloud guide's jump panel when the user opens it.
- **`AndroidBridge`** (`StreamBridge`) is the JS↔Kotlin channel: `setDiscordEnabled`, `openNotes`,
  `getDeviceStatusJson`.

Anchors worth knowing by name: `INJECT_SCRIPT`, `setupWebGLCAS`, `foundVideo`, `isStreamVideo`,
`injectDiscordToggle`, `armJumpPanelWatch`, `StreamBridge`.

---

## Video Pipeline — The Core Decision

### Why bridge canvas instead of VideoFrame

`new VideoFrame(video)` + `gl.texSubImage2D` was tried and confirmed working (no black screen, no
choppiness) but measured **worse battery** than the bridge canvas approach.

Root cause: On Android WebView / Adreno 618, VideoFrame→texSubImage2D does not stay GPU-resident.
The source is a `GL_TEXTURE_EXTERNAL_OES` buffer (gralloc NV12 from MediaCodec). Converting that to
a standard WebGL `sampler2D` requires an extra external-OES→2D resolve pass, and texSubImage2D on
Android Chromium often falls into a CPU staging path when source colorspace/format doesn't match.
This extends the GPU's busy window and pushes Adreno DCVS up a power level.

The bridge canvas path (`drawImage` → `texImage2D`) is counterintuitively better because:
- Canvas 2D `drawImage` has been heavily optimised in Chromium for years and stays GPU-resident end to end
- `texImage2D` with a canvas source allows Chromium to swap SharedImages (handle swap, no copy)
- `texSubImage2D` forces a write into existing storage, bypassing the SharedImage mechanism

**Do not switch to VideoFrame + texSubImage2D. Do not switch canvas + texSubImage2D either —
texImage2D is the correct call for the bridge path.**

### Why texImage2D not texSubImage2D for the bridge canvas

`bridge canvas + texSubImage2D` was tested and measured **higher battery** than `texImage2D`. Two
mechanisms at play:

1. **Texture orphaning:** `texImage2D` implicitly orphans the old backing store — if the GPU is
   still reading the texture from the previous `drawArrays`, the driver hands a fresh buffer
   immediately with no stall. `texSubImage2D` updates in place and may require a GPU pipeline
   barrier (sync stall) before the write can proceed. On Adreno 618's ANGLE/GLES2 driver, this
   shows up as increased power.

2. **SharedImage path:** `texImage2D` with a canvas source allows Chromium to swap SharedImages
   (handle swap, no pixel copy). `texSubImage2D` forces a write into existing storage, bypassing
   this mechanism. Note: this advantage disappears if the source were `video` directly — the bridge
   canvas already breaks any zero-copy path. Both calls receive CPU pixels by the time they run;
   the orphaning difference is what matters here.

**Do not switch to `texSubImage2D` for the bridge canvas. Do not pre-allocate with `texStorage2D`.
`texImage2D` every frame is correct.**

---

## Render Loop

### Current design

- **rVFC** (`requestVideoFrameCallback`) calls `present()` directly — drawImage → texImage2D →
  drawArrays happen the moment the decoder produces a frame, then rVFC re-arms itself.
- **rAF** still spins unconditionally every frame, but it no longer draws. It is:
  1. the **liveness ticker** — every 60th tick it checks `video.srcObject` and
     `document.contains(video)` and calls `teardown(video)` if the stream is gone. This replaced a
     parent-scoped removal MutationObserver.
  2. the **fallback draw path** — `if (!hasRVFC) present()`, for the case where
     `requestVideoFrameCallback` is unavailable.

`present()` early-returns unless `video.readyState >= 2 && !video.paused && !document.hidden`.

### Why the draw moved into rVFC — and why this is NOT the rejected design

**Measured and confirmed good.** Previously rVFC set a `newFrame` flag that the *next* rAF tick
acted on. That cost up to a full vsync of latency before the frame was even copied, and put the GPU
work on the vsync critical path. Draw count is unchanged: rVFC fires exactly once per decoded
frame, which is precisely what the old flag gated on.

**This is a different change from the one that was rejected.** Read carefully before touching this:

- **Rejected (~60% battery increase, ~5% per 15 min vs ~3.25% baseline):** removing the continuous
  rAF loop entirely, so rVFC → one-shot rAF → draw, with nothing spinning in between. Two variants
  of this were tried and both regressed. Likely cause: rVFC fires when the decoder produces a
  frame, which is not vsync-aligned. On Adreno's TBR architecture, GPU work is most efficient when
  aligned to vsync — one GPU wakeup handles both shader work and compositor work. Off-vsync work
  with no warm loop causes two GPU wakeups per frame instead of one.
- **Shipped and confirmed:** keep the always-spinning rAF loop, move only the *draw* into rVFC.

The always-spinning loop is intentional and load-bearing — it keeps the compositor and CPU governor
warm. **Do not "optimise" it away by making it event-driven, and do not delete it because "rVFC
already drives the draw."** That is exactly the change that cost 60%.

### Why not draw on every rAF tick

`preserveDrawingBuffer: false` + `desynchronized: true` means the OS compositor re-presents the last
submitted surface on ticks where no new draw occurs. There is no need to redraw duplicate frames —
this is what eliminates shader invocations for e.g. 30fps content on a 60Hz display.

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

Previously, neighbor UVs (vUVb/d/f/h) were computed in the vertex shader to shift 4 additions from
per-pixel to 3 per frame. That required a `texelSize` uniform updated on every resize. Replaced with
`textureOffset` in the fragment shader — the GLSL compiler folds constant `ivec2` offsets into the
sampler at compile time (zero runtime ALU cost), so the per-pixel arithmetic difference is gone.
Eliminates 4 varyings and the uniform, reducing interpolator bandwidth and the resize codepath.

Geometry is a single full-screen triangle (`triVerts`, 3 vertices), not a quad — no diagonal seam,
one fewer vertex, no index buffer.

**Do not reintroduce `texelSize` or neighbor UV varyings.**

### Fragment shader

Pipeline per pixel: 5-tap cross → luma min/max → CAS weight → soft-limited sharpen → saturation
boost.

- **`textureOffset(data, vUV, ivec2(...))`** — constant-offset variant; GLSL ES 3.0 core. Compiler folds the offset into the texture fetch. Equivalent cost to a plain `texture()` call. Offsets ±1 are well within `gl_MinProgramTexelOffset`/`gl_MaxProgramTexelOffset` range.
- **`precision mediump float`** — Adreno 618 runs mediump on FP16 ALUs (~2× throughput vs highp). Do not change to highp.
- **`const vec3 lw`** — compile-time constant enables driver constant folding on all `dot(x, lw)` calls. Do not make it a uniform.
- **`const float sharpenFactor = 0.37`** — compile-time constant, no uniform lookup needed. This is the tuned strength; changing it changes the look.
- **`amp = mn_l / (mx_l + 0.01)`** — the CAS adaptive term, already in simplified form (3 GPU ops: add, RCP, mul). No `sqrt` — it was removed.
- **Dark gate `wm = clamp((le - 0.05) * 2.2222, 0, 1)`** — suppresses sharpening in dark areas so stream noise in shadows is not amplified. Doubles as the saturation-boost weight.
- **Contrast gate `cg = clamp((mx_l - mn_l - 0.005) * 28.57, 0, 1)`** — suppresses sharpening on near-flat regions. Without it, compression noise and banding in smooth gradients (skies, walls, UI backgrounds) get sharpened into visible texture. Fades in over a narrow luma-range window rather than switching hard, to avoid a visible threshold edge.
- **Soft limiter `detL / (1.0 + abs(detL) * 4.0)`** — bounds the sharpening delta instead of letting it apply linearly. This is the halo fix: hard edges would otherwise overshoot into bright/dark ringing. Compresses large deltas, leaves small ones nearly untouched.
- **Saturation boost** (`satBoost = 1.0 + wm * 0.18`) — intentional, user wants this visual style. Applied both to the luma sharpen amount and the chroma expansion. Weighted by `wm` so dark areas are not over-saturated. **Do not remove.**
- **`vec3(sharpL) + (e - vec3(le)) * satBoost`** — luma-preserving chroma expansion; final `clamp` to [0,1].
- **5-tap cross pattern** — minimum for isotropic sharpening. Dropping to 4 taps requires an asymmetric or diagonal pattern with visible quality loss on game content. Do not reduce.

### Things that don't help the shader

- `fma()` — compiler already generates FMA for `a*b+c` patterns
- `inversesqrt` for amp — different formula AND slower (4 ops vs 3)
- Moving the draw back onto every rAF tick — wastes shader invocations on duplicate frames
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
- **Reapply `imageSmoothingEnabled`/`globalCompositeOperation` after ANY `bridge.width`/`bridge.height` assignment** — per HTML spec, setting a canvas's dimensions resets its 2D context to default state. `_syncSize` reapplies both after resizing. Before this fix (2026-07-02) the settings were wiped before the first frame and 'copy' was never active in production; the ~3.25%/15min battery baseline was measured with default 'source-over'. Not yet re-measured with 'copy' active.
- **`desynchronized: true` on bridge** — irrelevant, bridge canvas is off-screen and never composited. Do not add.
- Bridge canvas lives at IIFE scope (not inside `setupWebGLCAS`) — reused across context loss/restore events without reallocating
- **Sizing:** `_syncSize` sets both the output canvas and the bridge to `video.videoWidth/Height` (native stream resolution, no fractional scaling) and updates the viewport. It is debounced 16ms via `syncSize` for `resize` events, but called directly for `loadedmetadata` and at setup.
- **`webglcontextlost` restores `video.style.visibility = ''`** — the handler calls `preventDefault()` but nothing guarantees `webglcontextrestored` ever fires (e.g. GPU process crash). Without this, the video stays hidden and the screen is permanently black. Worst case must degrade to unfiltered video, not black. Do not remove.

### Output canvas placement

```js
canvas.style.cssText = 'position:fixed;inset:0;width:100%;height:100%;pointer-events:none;contain:strict;';
document.body.appendChild(canvas);
```

**Appended to `document.body`, not to xCloud's stream container.** Parenting it inside the xCloud
container was the cause of the "CAS stops working after opening the guide" bug (resolved
2026-06-10) — the guide's DOM churn tore the canvas out. Fixed-position on body is independent of
xCloud's layout entirely. **Do not reparent it.**

---

## Stream Video Acquisition

This is the subtlest part of the codebase. Read it before changing anything about how the video
element is found.

### The problem

xCloud plays **three different `<video>` elements** over a session: the Xbox splash animation, a
rocket loading animation, and the real stream. Binding to either of the first two is why CAS
appeared not to activate — the pipeline was running correctly, just on the wrong element.

### `isStreamVideo(v)`

Identifies the real stream. The element carries no id, so the checks are:
- `tagName === 'VIDEO'` and **no `src`** — the stream is fed via `srcObject`, the decoys have `src`
- className does not start with `XboxSplashVideo` or contain `RocketAnimationVideo`
- `v.parentElement?.dataset.testid === 'media-container'` — the preferred anchor, the only stable
  structural marker
- falls back to `!!v.srcObject` alone, so an upstream markup change degrades to a looser match
  instead of never binding at all

### Three acquisition paths

1. **Primary — `HTMLMediaElement.prototype.play` patch.** Catches the element the instant xCloud
   plays it, **even while it is still detached from the DOM.** This is the key insight: capture-phase
   listeners on `document` never see events from a node outside the tree, which is why an
   event-only approach missed the stream entirely.
2. **Secondary — capture listeners** for `loadedmetadata` and `playing` on `document`, in case the
   stream video was already playing before the script was injected. Costs nothing until a media
   event actually fires. An `emptied` listener calls `teardown` on any bound video.
3. **Warm case — synchronous sweep.** At the end of the IIFE, `document.querySelectorAll('video')`
   is scanned once for the already-streaming case (WebView restore, or re-injection after a real
   navigation) where neither hook above would ever fire.

**A `document.body { subtree: true }` MutationObserver was the old approach and is deleted.** Do not
bring it back — see the CPU-drain note under Guide Injection.

### Binding guards

- **`bindWhenSized(v)`** — binds immediately if `videoWidth` is known, otherwise waits for a
  one-shot `loadedmetadata`. Prevents setting up the pipeline against a 0×0 canvas.
- **`dataset.gxBound`** — set by `foundVideo`, checked by every entry path. All three acquisition
  paths can fire for the same element; this makes double-binding impossible.
- **`dataset.casSetup`** — the equivalent guard one level down, inside `setupWebGLCAS`.
- **`teardown(video)`** — full unbind: runs `_gxMenuCleanup`, runs `_casCleanup`, deletes
  `gxBound`. The `gxBound` delete matters: xCloud may re-use the same `<video>` element for the
  next stream, and without it that stream would never bind.
- **`_casCleanup`** — cancels rAF/rVFC, removes all listeners, clears the sync timer, removes the
  canvas, shrinks the bridge to 1×1, force-loses the GL context via `WEBGL_lose_context`, and
  restores `video.style.visibility`.

---

## Quick-Actions Toggle Hiding

xCloud's floating "Quick actions toggle" button overlays the stream. `hideMenuButton()` hides its
container and wires hover/touch handlers to reveal it on demand (touch reveals for 1s).

- **Returns a boolean** — `true` once hidden (or already hidden), so callers know to stop retrying.
  Idempotent and cheap to call repeatedly.
- **Retry ladder `[150, 400, 1000, 2500, 5000]`** — the toggle usually renders in the same commit
  as the video, so `tryHide` checks synchronously first. **A MutationObserver alone starves here:**
  the stream page goes DOM-quiet once playing, so an observer would only wake on guide open/close
  churn. The bounded ladder covers the slow-render case and then gives up.
- **The scoped observer is armed only after a successful hide**, watching
  `container.parentNode { childList: true }` — narrowly scoped, to re-hide if xCloud re-renders the
  toggle and drops the `dataset.hidden` marker.
- `_gxMenuCleanup` clears the timer, exhausts the attempt counter, and disconnects the observer.

---

## Guide / Jump Panel Injection

### Entry point is a gamepad probe, not an observer

`window.__gxcloudProbeJumpPanel` is exposed to Kotlin and called from `dispatchKeyEvent` on
ACTION_UP of `BUTTON_MODE`, `MENU`, or `BUTTON_START` — the buttons that open the Xbox guide. This
means nothing is watching the DOM during normal streaming.

`armJumpPanelWatch()`:
- **Warm path:** if `#guide-tabpanel-jump` is already in the DOM, inject immediately — zero observer
  cost. This is the common case.
- **Cold path:** the panel renders after arbitrary streaming lag, so a MutationObserver on
  `document.documentElement { childList, subtree: true }` waits for it — but it is **bounded**: it
  disconnects on first success and a 3s safety timeout kills it regardless. `jumpWatchArmed`
  prevents stacking observers across repeated probes.

**A permanent `document.body { subtree: true }` observer during streaming caused sustained CPU
drain** — it fired on every React DOM mutation. That is why every observer here is either narrowly
scoped or time-bounded. **Do not widen or unbound these.**

### What gets injected

Into `#guide-tabpanel-jump`'s `section[data-auto-focus="true"]`: a **Notes** item and a **Discord
toggle**. A `reinjector` MutationObserver watches that section (`childList` only, not subtree) and
re-inserts either element if React removes it.

### Removal detection is an IntersectionObserver

`watchForJumpPanelRemoval(panel)` uses an **IntersectionObserver** (threshold 0), not a
MutationObserver. When the panel stops intersecting, it disconnects the reinjector, clears
`dataset.discordInjected`, and removes the status overlay. Cheaper than watching mutations and it
correctly handles the panel being hidden rather than removed.

### Injected CSS

```
* { -webkit-tap-highlight-color: transparent !important; outline: none !important; }
button[aria-label="Exit preview"] { visibility: hidden !important; }
```

**The universal `*` selector is required.** Narrowing to `video,canvas` causes the Discord toggle to
stop appearing in the guide panel (exact mechanism unclear, likely xCloud's guide CSS interacting
with a missing `outline: none` on section/container elements). **Do not narrow this selector.**

---

## Status Overlay

Battery percentage + local time, injected into `document.documentElement` when the guide panel is
injected, removed when the IntersectionObserver sees the panel leave.

- Battery comes from `AndroidBridge.getDeviceStatusJson()` → `BatteryManager.BATTERY_PROPERTY_CAPACITY`.
  Returns `-1` on failure; the overlay renders `--%`.
- **`all:initial`** on the container and each span — the overlay lives in xCloud's DOM, and this is
  what stops xCloud's stylesheets from restyling it. Keep it.
- `pointer-events:none`, `z-index:2147483647`, fixed top-right.
- Snapshot only — rendered once when the guide opens, never ticks. The guide is transient, so a
  timer would be pure battery cost for no visible benefit. **Do not add an interval.**
- **Known wart:** the time is hardcoded to `timeZone: 'America/New_York'`. Fine for the current
  user, wrong for anyone else. Dropping the `timeZone` option entirely would use device local time.

---

## Discord Overlay

### State machine

Two states: `CLOSED ↔ UI_VISIBLE`. 4 rapid taps (within 600ms) anywhere on screen opens Discord when
`discordEnabled` is true; 4 more taps closes it. Taps landing inside the Discord WebView's bounds do
not count while it is visible (`isTapOnDiscord`), so normal Discord interaction can't accidentally
close it.

### Toggle gates everything

`discordEnabled` (set via `AndroidBridge.setDiscordEnabled()` from the injected JS toggle) is the
single gate. When false and the state is CLOSED, the early-return in `dispatchTouchEvent` fires
immediately — no Discord WebView is inflated, no network activity, no battery cost. Do not add
secondary guards.

### Lazy inflation

The Discord WebView lives in a `ViewStub` (`discordStub`) inflated on first `openDiscord()`. It
never exists for users who don't enable the feature.

### Microphone permission

Requested on the first `openDiscord()` call, not at startup. Discord defaults to disabled so
requesting at startup would prompt before the user opts in.

### Discord WebView optimisations

- **`RENDERER_PRIORITY_IMPORTANT`** when UI is visible — ensures Discord renders correctly
- **`RENDERER_PRIORITY_WAIVED`** on close — deprioritises renderer process when not visible
- **`about:blank` on close** — releases Discord's network connections and DOM memory
- Linux/Chrome desktop UA — Discord Web gates features on a desktop user agent
- `MIXED_CONTENT_NEVER_ALLOW`, no file/content access, `onPermissionRequest` grants **only**
  `RESOURCE_AUDIO_CAPTURE` and denies everything else

### Why a separate WebView for Discord

Discord Web requires `domStorage`, media permissions, and a desktop user-agent. Sharing the main
WebView with Xbox would require constant URL switching and lose game session state. A separate
WebView lets both run independently.

---

## Notes

A minimal local notepad, opened from the guide item via `AndroidBridge.openNotes()`.

- Lives in a `ViewStub` (`notesStub`), inflated on first open — zero cost until used.
- Persistence: `AtomicFile` at `filesDir/gxcloud_notes.json`, a JSON array of
  `{id, title, body, updatedAt}`. **AtomicFile, not a plain File** — a crash or kill mid-write on a
  handheld must not corrupt the notes; `failWrite` rolls back.
- **Autosave is debounced 600ms** (`scheduleNoteSave`) so typing doesn't hit storage per keystroke.
  `forceNoteSave()` flushes immediately on `onPause`, on close, on switching notes, and before
  creating a new note.
- `notesLoaded` gates `saveNotes()` — prevents an empty in-memory list from overwriting the file
  before the first load completes.
- The list always keeps at least one note; deleting the last one creates a fresh empty note.
- Back button precedence: Notes visible → hide Notes; otherwise → WebView history.
- Load failures are swallowed and treated as "no notes" rather than crashing.

---

## Android / Kotlin

- **`preferMinimalPostProcessing = true`** — disables SurfaceFlinger HDR tone-mapping and display post-processing. Real battery saving.
- **`preferredDisplayModeId` → 60Hz** — prevents display running at higher refresh rates for a 60fps stream
- **`LAYER_TYPE_NONE`** — WebView default. Do not change to `LAYER_TYPE_HARDWARE` (adds an extra off-screen compositing texture wrapping a WebView that already does its own GPU rendering)
- **`setBackgroundDrawable(null)` + black WebView bg** — eliminates window background overdraw
- **`importantForAutofill = NO`, `importantForAccessibility = NO`** — prevents autofill/accessibility scans on focus, saves CPU
- **`isHapticFeedbackEnabled = false`** — prevents haptic driver calls from touch events
- **`isLongClickable = false`** — suppresses context menu; `setOnLongClickListener { true }` is redundant if this is set
- **`setOnHoverListener { _, _ -> true }`** — swallows hover events. Controller/trackpad hover would otherwise trigger xCloud hover states and WebView hit-testing for no benefit.
- **`isSaveEnabled = false`** / `isSaveFromParentEnabled = false` — no WebView state worth restoring; prevents serialization on activity recreation
- **`setOffscreenPreRaster(false)`** — prevents pre-rasterization of offscreen content
- **`RENDERER_PRIORITY_IMPORTANT` on resume / `pauseTimers()` + `RENDERER_PRIORITY_WAIVED` on pause** — applies to the **main** WebView, not just Discord. Stops JS timers and drops renderer priority when backgrounded.
- **`FLAG_KEEP_SCREEN_ON`** — gaming session, screen must not sleep
- **`systemUiVisibility` flags** — confirmed hardware compatibility requirement on this device. Do not replace with `WindowInsetsControllerCompat`. This is a known incompatibility. Re-applied in `onWindowFocusChanged`.
- **`AUDIOFOCUS_GAIN`** — correct for a multi-hour gaming session. `AUDIOFOCUS_GAIN_TRANSIENT` is for brief interruptions and would allow other apps to resume audio mid-game.
- **`MODE_NIGHT_YES` set before `super.onCreate`** — avoids a re-create for a config change
- **`largeHeap` removed** — WebView runs in a separate renderer process; largeHeap only affects the tiny main process. No benefit.
- **`forceDark = FORCE_DARK_OFF` / `setAlgorithmicDarkeningAllowed(false)`** — prevents WebView from applying color inversion on top of Xbox's already-dark UI. Likely no measurable battery saving since the site is already dark, but correct.
- **`onDestroy` nulls the clients before `destroy()`** on both WebViews — avoids callbacks into a torn-down Activity.

---

## Input Routing

- **`dispatchKeyEvent`** — `KEYCODE_BACK` goes straight to `super` (so the back dispatcher handles
  it). Everything else is offered to the WebView **first**, then `super`. Gamepad input must reach
  the page before the framework consumes it.
- **`BUTTON_MODE` / `MENU` / `BUTTON_START` on ACTION_UP** additionally fire
  `window.__gxcloudProbeJumpPanel()` — these are the guide-opening buttons, and this is what
  triggers panel injection. The call is guarded by `&&` in JS so it is harmless before injection.
- **`dispatchGenericMotionEvent`** — analog stick/trigger motion, WebView first, then `super`.
- **`dispatchTouchEvent`** — only inspects ACTION_DOWN for the Discord 4-tap gesture; always calls
  through to `super` so no touch is ever swallowed.

---

## CSS / JS

- **`canvas.style.contain = 'strict'`** — tells browser this canvas is independent from page layout; prevents layout/paint recalculation from propagating through/to the canvas
- **`overscrollBehavior = 'none'`** on both `documentElement` and `body` — prevents pull-to-refresh and overscroll effects
- **`window.__gxcloudInjected` guard** — `INJECT_SCRIPT` runs on every `onPageFinished`; the IIFE
  returns immediately if it has already run in this document
- **`onPageFinished` checks `url == view.url`** — skips injection for stale/iframe page-finish callbacks
- WebGL canvas is already on its own GPU compositor layer by definition. `translateZ(0)` and `will-change: transform` are no-ops on WebGL canvases.
- `video.style.willChange = 'transform'` — pointless on a hidden video, would just waste a compositor layer

---

## What Was Measured and Confirmed

| Change | Result |
|--------|--------|
| VideoFrame + texSubImage2D | Smooth but higher battery than bridge — rejected |
| Direct upload: `gl.texImage2D(…, video)` (no bridge canvas) | 4%/15min vs ~3.25% bridge baseline (~23% worse) — rejected. Consistent with external-OES resolve theory: VideoFrame source is GL_TEXTURE_EXTERNAL_OES; resolving to sampler2D adds a CPU staging path. Bridge canvas avoids this entirely. |
| Bridge canvas + texImage2D | Best battery — current approach |
| Bridge canvas + texSubImage2D | Higher battery than texImage2D (pipeline barrier stalls) — rejected |
| Pure rVFC drives RAF, **no continuous rAF loop** | ~60% battery increase vs baseline — rejected |
| rVFC calls `present()` directly, **continuous rAF loop retained** | Confirmed good — current approach. Removes up to a vsync of latency at unchanged draw count. Not the same change as the row above. |
| rAF + rVFC hybrid with `newFrame` flag | Previous approach, superseded |
| `document.body { subtree:true }` observers during streaming | Sustained CPU drain — all observers now scoped or time-bounded |
| NEAREST vs LINEAR filtering | NEAREST confirmed no quality loss at native res, less texture unit work |
| `textureOffset` vs neighbor UV varyings | Same visual output, fewer varyings, no texelSize uniform — current approach |
| preferMinimalPostProcessing | Real battery saving |
| 60Hz display pin | Real battery saving |

---

## Open Issues

Both verified still present in the current code.

- **Discord mic permission race.** `openDiscord()` calls `requestPermissions` then immediately
  calls `loadUrl` without waiting for the result, and there is no `onRequestPermissionsResult`
  override. On first use, Discord loads before mic is granted — voice is broken until app restart.
  Fix: defer `loadUrl` until the permission result arrives.
- **Discord close→open freeze.** `onPageFinished("about:blank")` calls `view.onPause()`
  unconditionally. A fast close→reopen can race with `onResume()` from `openDiscord()` and leave the
  WebView frozen. Fix: check `discordState` inside `onPageFinished` before calling `onPause()`.
- **Hardcoded status-overlay timezone** — `America/New_York`, see Status Overlay above. Cosmetic.

---

## Manifest

- **`android:screenOrientation="landscape"`** — gaming handheld, always landscape
- **`android:configChanges`** — covers orientation/screen/keyboard to prevent Activity recreation
- **`android:launchMode="singleTask"`** — single instance
- **`android:hardwareAccelerated="true"`** — default for API 14+, explicit for clarity
- **`android:resizeableActivity="false"`** — no multi-window; avoids resize/relayout paths
- **`android:windowSoftInputMode="stateAlwaysHidden|adjustNothing"`** — the IME must never resize
  the stream surface (Notes has its own EditText, and it is fine with this)
- **`android:usesCleartextTraffic="false"`** / `allowBackup="false"` — no plaintext, no cloud backup of session state
- **`android:largeHeap` removed** — WebView is a separate process, largeHeap had no effect
- **`android:appCategory="game"`** — **unmeasured, added on theory (2026-07-17).** Correct declaration either way (this is a game app), zero risk. Groups the app under Games in Settings battery/data/storage attribution, and is how the platform identifies games for Game Mode. Caveat: the Game Mode API is Android 12+ (API 31); if the G Cloud is on Android 11 that codepath doesn't exist and this attribute buys nothing beyond attribution grouping and whatever Logitech's own game detection does with it. Do not count this as a battery optimisation — no measurement backs it.
- Permissions: `INTERNET`, `RECORD_AUDIO` + `MODIFY_AUDIO_SETTINGS` (Discord voice only)
