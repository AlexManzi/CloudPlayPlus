# Render pipeline test plan

Status: initial bridge and subimage measurements completed; further tests pending.

## Initial results — 2026-10-31

| Pipeline | Mean discharge | Median discharge | Samples |
| --- | ---: | ---: | --- |
| Installed bridge app | 790.0 mA | 795.9 mA | 301 over 299.6 s |
| Installed bridge app, repeat | 827.4 mA | 832.8 mA | 301 over 299.0 s |
| Bridge + `texSubImage2D` test app | 782.4 mA | 785.8 mA | 301 over 299.4 s |
| VideoFrame + `texSubImage2D` test app | 800.9 mA | 804.7 mA | 301 over 299.0 s |
| Direct video + `texImage2D` test app | 746.8 mA | 745.2 mA | 301 over 299.4 s |
| Direct video + `texImage2D` repeat | 712.7 mA | 710.4 mA | 301 over 299.7 s |
| Direct video + `texSubImage2D` test app | 791.6 mA | 802.3 mA | 301 over 299.3 s |
| Updated main source, bridge removed, process diagnostics | 759.5 mA | 763.5 mA | 301 over 299.8 s |

The updated main-source diagnostic run adds scheduler/process tracing, unlike the
earlier battery-only runs. It also uses current source CAS tuning (Normal 0.43,
saturation 0.19) rather than the isolated apps' extracted settings (0.37/0.18);
actual selected mode was not independently confirmed. It cannot isolate the effect
of bridge removal. Total power was approximately 2.87 W using endpoint voltages.
Process CPU breakdown and limitations are saved locally in
`captures/render-tools/direct-cleanup-process-2026-10-31.md`.

The subimage run was 7.6 mA (~1.0%) lower in mean discharge. These are single
whole-device runs, not enough to establish an improvement. The user chose to use the
installed-app bridge run as the baseline without repeating it. The subimage test used
the installed APK's extracted video script with allocation on resize and subimage upload;
its release-mode Android wrapper was rebuilt from available source and is not confirmed
identical to the installed app. Conditions and CAS mode have not been fully recorded.
Subimage had a one-minute warm-up; the bridge test's warm-up duration was not recorded.
The VideoFrame test also had a one-minute warm-up; its mean was 10.9 mA (~1.4%) higher
than the bridge run and 18.5 mA (~2.4%) higher than subimage. Each is one run, so these
small differences do not establish a reliable ranking. The VideoFrame test uses a separate
release-mode wrapper rebuilt from available source, and still allocates an unused bridge
canvas at setup; it skips the per-frame bridge copy. Frames are closed after upload.
The direct-video run used a one-minute warm-up and averaged 43.2 mA (~5.5%) less
discharge than the bridge run. This is a single run per option, with unverified matching
conditions and rebuilt test-app wrappers; the result does not establish a reliable saving.
The direct-video variant still allocates an unused bridge canvas during setup. The device
was discharging and the direct-video test app was foreground after the capture; battery
was 60%, 3.834 V, and 32.1 °C. Playback quality and CAS selection remain unconfirmed.
Direct video + subimage was added as a fifth candidate and averaged 791.6 mA, 44.8 mA
(~6.0%) above direct video + `texImage2D` in these single runs. It had a one-minute
warm-up, was the foreground test app after capture, and the device was discharging;
battery afterward was 55%, 3.800 V, and 33.0 °C. The same single-run, conditions, and
wrapper limitations apply. Its raw trace is in `captures/render-tools/`.
The direct-video repeat averaged 712.7 mA, 34.0 mA below its first run and 77.3 mA
below the single bridge baseline. Warm-up and matched scene/settings were not confirmed;
device was discharging and the correct app was foreground before and after the capture.
Afterward battery was 53%, 3.798 V, and 33.1 °C. A second direct-video run does not
resolve the single-baseline or differing-wrapper limitations. Its raw trace is in
`captures/render-tools/`.
The bridge repeat averaged 827.4 mA (median 832.8 mA), with a one-minute warm-up;
the installed normal app was foreground before and after, and the device was
discharging. Battery was 52%, 3.786 V, 33.3 °C before warm-up and 50%, 3.759 V,
34.3 °C afterward. The preceding direct-video repeat averaged 712.7 mA (median
710.4 mA), a 114.7 mA (~13.9%) lower mean than this bridge run. Scene, stream
parameters, CAS selection, and device conditions were not independently confirmed;
installed and rebuilt test wrappers may differ. Two runs per path show a consistent
direction but do not establish a precise saving or isolate causation. The raw bridge
repeat trace is in `captures/render-tools/`.

The installed APK reports version 1.2.3/code 5, but its version label may be stale;
its video script is the authoritative baseline, not the published tag. Its extracted
Normal CAS strength is 0.37 and saturation factor is 0.18. WebView was 156.0.8078.47.
The local raw traces and detailed records are in the ignored `captures/render-tools/`
directory and are not included in Git. Playback quality and input feel remain unrecorded.

All five candidates have been measured; direct video + `texImage2D` has two runs. Decide with the user whether to
repeat tests or investigate differences before adopting a pipeline change.

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
   until each candidate has at least three runs, if the user chooses to repeat the baseline.
  The user has currently chosen to keep the original single bridge run. Compare candidates
  with it and clearly report the resulting limitations;
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
