# Render pipeline test plan

Status: planned; no experiments or measurements have been performed under this plan.

The user wants to revisit video upload alternatives and compare real device measurements
against the current bridge pipeline. This explicit retest supersedes the earlier decision
in `AGENTS.md` to close these experiments; the old results remain useful context, not
evidence that a candidate will win today. Keep CAS and the user's current shader tuning.
Do not change the production pipeline based on this plan alone.

## Candidates

Change only the frame source and upload operation for each candidate. Keep the same CAS
shader, strengths, output size, filtering, rVFC drawing cadence, continuous rAF loop,
watchdog, cleanup, and lifecycle handling. Label each test build and record its commit
and local changes; preserve the existing working tree and user changes.

| ID | Frame source and upload | Previous observation |
| --- | --- | --- |
| B | 2D bridge `drawImage` → `texImage2D` | Current baseline; previously best observed battery. |
| C | 2D bridge `drawImage` → `texSubImage2D` | Previously higher drain. Allocate and resize texture correctly before subimage uploads. |
| V | `new VideoFrame(video)` → `texSubImage2D` | Previously smooth but higher drain. Close every frame, including on errors; handle size changes. |
| D | Direct video → `texImage2D` | Previously about 4% per 15 min vs ~3.25% for the bridge. |

Confirm API support and equivalent texture format, dimensions, orientation, and output for
each variant. Record unsupported or failing variants rather than silently falling back.
Do not test WebGPU: the earlier device test crashed the app. It requires a separate
decision and the safeguards described in `AGENTS.md`.

## First: retest the current implementation

Before building or changing any rendering variant, run the existing app with its current
bridge → `texImage2D` pipeline and CAS settings for a dedicated five-minute measurement.
Record the exact installed build, source revision and local changes where known; do not
assume an installed APK matches the working tree. Use the setup and controls below, warm
up for at least two minutes, then capture battery current with Perfetto for five minutes.
Record mean and median current, voltage and approximate watts if available, temperature,
stream resolution/frame rate, and playback observations. Save the raw trace and conditions.

This first run establishes a fresh reference and checks the measurement setup. It is one
sample, not a conclusive baseline: retain the interleaved bridge runs below to account for
noise and drift when comparing alternatives.

## Before measuring

1. Record the G Cloud's Android and WebView versions, app revision and local diff,
   build type, battery level, temperature, date, and candidate identifier. Capture the
   shader constants and CAS mode, including any uncommitted tuning.
2. Use the same Remote Play console, game, repeatable scene, stream resolution and frame
   rate, network and connection path, brightness, volume, Bluetooth audio setup, Discord
   state, and device settings throughout. Keep the stats page closed during measurements.
   Record any differences, reconnects, dropped frames, interruptions, or thermal changes.
3. Build isolated, clearly identified variants without overwriting the user's release
   artifact. Run applicable JavaScript tests and verify playback, image orientation and
   quality, resize, CAS switches, stream exit, and context-loss cleanup before collecting
   power data. Reject a broken or visibly degraded candidate even if its power is lower.
4. Connect ADB over Wi-Fi, unplug USB, and verify the battery reports discharging. Check
   that Perfetto battery current and timestamps are available and establish the current
   sign and units. Keep tracing settings identical across runs.

## Measurement procedure

- Warm up the same scene and stream for at least 2 minutes after each switch. Measure
  each run for 5 minutes using Perfetto `android.power` current sampled at 1 second;
  capture CPU/GPU frequency and scheduling where available. Use the same trace setup
  throughout. Record battery voltage too if available for approximate total watts.
- Collect at least three runs per candidate on the same device. Interleave baseline runs
  to expose drift: B → C → B → V → B → D, then repeat in a different candidate order
  until each candidate has at least three runs. Compare each candidate with nearby B runs;
  keep the same warm-up and measurement windows. More runs or a second session are useful
  if thermal drift, battery changes, or noise obscure the result.
- Record mean and median discharging current for each run, plus spread or variability;
  calculate approximate watts from synchronized current and voltage when available.
  Compare run-level paired differences against nearby baseline runs, and report the
  individual runs and uncertainty rather than just the best result. Battery percentages
  are supplementary, not the primary measurement.
- Record stream frame rate, dropped frames, visual quality, input feel, glitches, and
  device temperature separately. Use comparable low-overhead collection in every run;
  avoid leaving an overlay open for only one variant. Note any latency measurement
  method and its limitations. A low-power result is not acceptable if playback degrades.
- Battery current is total-device power, not CAS-only or per-component power. CPU/GPU
  frequencies and thread activity provide context, not proof of the underlying cause.
  The historic ~700 mA CAS-on / ~617 mA CAS-off comparison was one 60-second run per
  mode on xCloud, with roughly ±30 mA noise; it is not the baseline for this new test.

## Results and decision

Save raw traces, build identifiers, test conditions, and a summary outside exported release
artifacts. For each run record candidate, order, duration, mean/median current, voltage or
watts if reliable, temperature, playback observations, and any deviations. Summarize the
paired differences and their variability against the contemporaneous bridge runs.

Only recommend a change if a repeatable improvement is distinguishable from measurement
noise and the image, smoothness, latency, and lifecycle behavior meet the user's needs.
Otherwise retain the bridge. Present the results and tradeoffs to the user before adopting
any candidate; update `AGENTS.md` with measured conditions and conclusions after testing.
