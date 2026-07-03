---
name: reapply-fork-fixes
description: Re-apply this fork's custom fixes after merging/pulling a new upstream release. Use when the user has just updated from the upstream repo they forked from and wants to restore fork-specific patches (Skylator identity, Xiaomi/HyperOS frame-gen + HUD fixes, in-game download refresh, frame-gen persistence + presets) that the update may have dropped or that upstream still hasn't resolved. Triggers: "after pulling upstream", "merged the new version", "re-apply my fixes", "patch after update", "port my fork fixes".
---

# Re-apply fork-specific fixes

This fork carries a small set of custom patches on top of upstream. When you
pull a new upstream release, some may be lost in the merge/rebase and some may
still be unfixed upstream. This skill walks each patch, detects whether it's
still needed, and re-applies the ones that are missing.

Each patch below is an **independent** real commit on this repo's history, so
`git show <SHA>` is the authoritative source of the exact change. Line numbers
in this file are approximate and WILL drift after an upstream update — always
match by surrounding code, never by line number.

## Workflow

1. **Confirm state.** Make sure the upstream merge/rebase is complete and the
   tree is otherwise clean (`git status`). Work on a branch, not detached HEAD.
   Note the current branch name.
2. **For each patch in the registry**, in order:
   1. Run its **Detect** command.
   2. Classify:
      - **already-applied** → the fix is present. Skip, say so.
      - **resolved-upstream** → the bug is gone but the fix isn't ours (upstream
        fixed it differently). Skip, tell the user upstream now handles it.
      - **missing** → the bug pattern is present and our fix isn't. Re-apply.
   3. To re-apply: `git show <SHA>` for the authoritative diff, then apply the
      same change to the *current* code, matching surrounding context and
      preserving any upstream refactors. If `git show <SHA>` fails (history was
      rewritten), fall back to the **Change** description here to re-derive it.
   4. Report the per-patch outcome (applied / skipped-already / skipped-upstream).
3. **Build & verify.** `./gradlew assembleStandardRelease`, then offer to
   `adb install -r app/build/outputs/apk/standard/release/app-standard-release.apk`
   onto the connected device. (This project has no unit tests — verification is
   on-device; see the notes at the end.)
4. **Summarize** which patches were re-applied vs already present vs now handled
   upstream.

> Tip: to keep these commits findable even after a rebase renumbers SHAs, the
> user can tag them once: `git tag fork-fix/<id> <SHA>`. Then use the tag in
> place of the SHA below.

## Patch registry

Scope legend: **identity** = fork branding (always re-apply after upstream
resets it); **xiaomi** = only matters on Xiaomi/HyperOS (and modern-Android)
devices but harmless elsewhere; **universal** = applies to all devices.

---

### 1. Skylator identity — `app/build.gradle`  ·  SHA `1c02887`  ·  identity

**What:** In the `standard { }` product flavor, `applicationId` is
`com.winlator.sky` and `resValue "string", "app_name"` is `"Skylator Bionic"`.
Upstream ships `com.winlator.banner` / "Bannerlator Bionic". Do **not** touch
the `ludashi` / `pubg` flavors (they intentionally spoof other package IDs).

**Detect:**
```
grep -nA3 'standard {' app/build.gradle | grep -E 'com\.winlator\.banner|Bannerlator Bionic'
```
Any match → the rename was reverted by the merge → re-apply (banner→sky,
"Bannerlator Bionic"→"Skylator Bionic" in the `standard` block only).

---

### 2. Xiaomi libjpeg symlink removal — `app/src/main/java/com/winlator/star/xenvironment/ImageFsInstaller.java`  ·  SHA `084c184`  ·  xiaomi

**Symptom:** On Xiaomi/HyperOS, frame generation (bionic-fg AND lsfg-vk) never
engages — the Vulkan loader logs `Requested layer "VK_LAYER_BIONIC_framegen"
failed to load` with `dlopen ... jsimd_huff_encode_one_block ... libjpeg-hyper.so`.

**Root cause:** Xiaomi's patched `libhwui.so` pulls `/system_ext/lib64/libjpeg-hyper.so`
into the frame-gen layer's dlopen closure (via `libandroid.so`), and
libjpeg-hyper's own `libjpeg.so` dependency resolves to the imagefs's bundled
`usr/lib/libjpeg.so` symlink, which lacks the `jsimd_*` symbols — aborting the
whole layer load.

**Change:** Add a method `removeLibjpegShadowIfXiaomi(ImageFs imageFs)` that, when
`/system_ext/lib64/libjpeg-hyper.so` exists, deletes `imageFs.getLibDir()/libjpeg.so`.
Call it at the top of `installBionicFgLayer(...)` (which also runs on every app
start via `installIfNeeded`, so existing installs self-heal). Nothing resolves
the bare `libjpeg.so` name at runtime, so removal is safe; guarded so non-Xiaomi
devices are untouched.

**Detect:**
```
grep -q 'removeLibjpegShadowIfXiaomi' app/src/main/java/com/winlator/star/xenvironment/ImageFsInstaller.java && echo APPLIED || echo MISSING
```
`MISSING` → re-apply (see `git show 084c184`).

---

### 3. In-game download list refresh — `app/src/main/java/com/winlator/star/ui/screens/ShortcutsScreen.kt`  ·  SHA `c81fce5`  ·  universal

**Symptom:** In a game shortcut's settings, downloading a DXVK/FEXCore/Box64/
VKD3D/Vegas version doesn't appear in the dropdowns until the whole settings
modal is closed and reopened.

**Root cause:** The version lists load once in `LaunchedEffect(Unit)` and every
download sheet passes `onContentChanged = {}` (a no-op), so nothing reloads.

**Change:**
- Add `var contentRefreshKey by remember { mutableStateOf(0) }` in the settings
  dialog composable.
- Split the Box64/FEX version-list loading into its own
  `LaunchedEffect(contentRefreshKey)` (re-runs `cm.syncContents()` + reassigns
  `box64Versions`/`fexCoreVersions`), leaving the selection/preset-index
  initialization in the original one-shot `LaunchedEffect(Unit)` so a refresh
  never clobbers unsaved dropdown choices.
- Replace all five `onContentChanged = {}` with `{ contentRefreshKey++ }`.
- Pass `refreshKey = contentRefreshKey` into the `DxvkConfigDialog(...)` call
  (its internal `LaunchedEffect(refreshKey)` reloads DXVK/VKD3D lists).
- Also fix the Box64 sheet to use `CONTENT_TYPE_WOWBOX64` when `isArm64EC` (it
  hardcoded `CONTENT_TYPE_BOX64`).

**Detect:**
```
grep -q 'contentRefreshKey' app/src/main/java/com/winlator/star/ui/screens/ShortcutsScreen.kt && echo APPLIED || echo MISSING
grep -n 'onContentChanged = {}' app/src/main/java/com/winlator/star/ui/screens/ShortcutsScreen.kt   # any hit => still buggy
```
`MISSING` (or any `onContentChanged = {}` remaining) → re-apply (`git show c81fce5`).

---

### 4. Performance HUD CPU + power — `app/src/main/java/com/winlator/star/widget/HudMetrics.java` (and `FrameRating.java`)  ·  SHA `7832253`  ·  xiaomi / modern-Android

**Symptom:** In-game HUD shows **CPU 0%** and **PWR 0.0W** (other fields fine).

**Root cause:**
- CPU: `getCPUUsage()` read the global `/proc/stat`, which is SELinux-blocked
  (`proc_stat` + `hidepid=invisible`) for apps on modern Android → returns 0.
- Power: `getBattery()` only computed watts when `microAmps < 0`; this device
  reports discharge as positive, so watts stayed 0.

**Change:**
- CPU: replace the `/proc/stat` read with the emulator's own process-tree usage
  — sum `utime+stime` across visible (same-UID) `/proc/<pid>/stat` (parse fields
  after the last `')'`), delta against `elapsedRealtimeNanos` × cores × `_SC_CLK_TCK`,
  clamped 0..100, first call seeds baseline. (`readProcessTreeCpuTicks()`.)
- Power: compute `watts = |microAmps| * mV / 1e9` for any nonzero, non-`MIN_VALUE`
  reading (sign-agnostic); if `|µA| < 10000` treat as mA and ×1000 (Xiaomi unit
  quirk). `charging` flag still drives the CHG/PWR label.
- Mirror the same power guard in `FrameRating.java` (no CPU reader there).

**Detect** (key on the fix marker `absUa`, not the bug string — our comments
mention `microAmps < 0`, so grepping the bug string false-positives):
```
grep -q 'readProcessTreeCpuTicks' app/src/main/java/com/winlator/star/widget/HudMetrics.java && echo CPU_APPLIED || echo CPU_MISSING
grep -q 'absUa' app/src/main/java/com/winlator/star/widget/HudMetrics.java && echo POWER_APPLIED || echo POWER_MISSING
grep -q 'absUa' app/src/main/java/com/winlator/star/widget/FrameRating.java && echo FR_POWER_APPLIED || echo FR_POWER_MISSING
```
Any `_MISSING` → re-apply (`git show 7832253`). If instead you find that
upstream now reads CPU from a per-process source and computes battery watts
sign-agnostically, that's **resolved-upstream** — skip.

---

### 5. Frame-gen persistence + flow presets — `container/Container.java`, `XServerDisplayActivity.java`, `ui/XServerDrawer.kt`  ·  SHA `bccb8d7`  ·  universal

**What it adds:**
- The in-game frame-gen on/off (multiplier) **persists across launches** instead
  of always starting Off. The Edit-screen engine dropdown stays master: if no
  engine is selected, the persisted on-state is ignored.
- A **flow-scale preset** segmented control (Eco 0.2 / Flow 0.4 / Balanced 0.6 /
  Boost 0.8 / Max 1.0) beneath the multiplier toggle, above the (kept) Flow Scale
  slider.

**Change:**
- `Container.java`: add `isFrameGenSessionOn()/setFrameGenSessionOn(boolean)`
  (extra `frameGenSessionOn`, default "0").
- `XServerDisplayActivity.java`: add helper `resolvedFrameGenSessionMultiplier()`
  (`isFrameGenSessionOn() ? getFrameGenMultiplier() : 0`); at launch seed the
  drawer multiplier with `bionicFgActive ? resolvedFrameGenSessionMultiplier() : 0`
  (was hardcoded 0); write that multiplier into the launch layer config (lsfg
  `mult>=2?mult:1`, bionic `mult`); in `onBionicFgConfigChange` persist
  `setFrameGenSessionOn(fgOn && mult >= 2)` in both engine branches.
- `XServerDrawer.kt`: add `FG_FLOW_PRESETS` + `FgPresetButtons(...)` (styled like
  `FgMultiplierButtons`), rendered in `FrameGenSection` above the Flow Scale
  slider; selecting a chip sets `fgFlow` and calls `applyFg()`.

**Detect:**
```
grep -q 'resolvedFrameGenSessionMultiplier' app/src/main/java/com/winlator/star/XServerDisplayActivity.java && echo ACT_APPLIED || echo ACT_MISSING
grep -q 'FgPresetButtons' app/src/main/java/com/winlator/star/ui/XServerDrawer.kt && echo DRAWER_APPLIED || echo DRAWER_MISSING
grep -q 'isFrameGenSessionOn' app/src/main/java/com/winlator/star/container/Container.java && echo CONTAINER_APPLIED || echo CONTAINER_MISSING
```
Any `_MISSING` → re-apply (`git show bccb8d7`). These three files are one logical
change — re-apply all three together.

---

### (low priority) JavaSteam jars gitignore — `.gitignore`  ·  SHA `26db892`

`/app/libs/steam/*.jar` should be gitignored (they're built locally, not
committed). Detect: `grep -q 'app/libs/steam' .gitignore`. Purely local hygiene;
re-add the line if missing.

## Notes for whoever runs this

- **Build/test loop:** `./gradlew assembleStandardRelease` →
  `adb install -r app/build/outputs/apk/standard/release/app-standard-release.apk`.
  There are no unit/instrumented tests; the app is arm64-only and needs a real
  device (Xiaomi POCO X6 was the reference device). The Xiaomi-scoped fixes (#2,
  #4) can only be verified on such a device, but are guarded to be no-ops
  elsewhere.
- **Building at all** needs the JavaSteam jars in `app/libs/steam/` (not
  committed) — build them the way CI does (see `.github/workflows/main.yml`,
  clone `joshuatam/JavaSteam` branch `gamenative-latest`).
- If a patch classifies as **resolved-upstream**, leave it out and tell the user
  — that's the whole point of the detection step.
