# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

Bannerlator (internally `com.winlator.star`, module `Winlator`) is an Android app that runs
Windows applications and games via Wine/Box64/DXVK on-device — no PC, no root. It's a personal
fork lineage: **Winlator → cmod → Bionic Nightly → Star Bionic → marcescence → Bannerlator**.
It is maintained for one person's own device/workflow, not as a general-purpose community
project — see the "Project Notice" at the top of `README.md` before assuming any change should
generalize to other hardware.

The standard flavor is currently rebranded **Skylator Bionic** (`com.winlator.sky`) as a
fork-local identity patch; `ludashi`/`pubg` flavors intentionally spoof other package IDs
(benchmark/game app disguises) and must not be touched by identity changes.

## Build & verification

- **No unit/instrumented tests exist in this repo.** Verification is build success +
  on-device testing (arm64-only; the reference device is a Xiaomi POCO X6, so HyperOS/Xiaomi
  quirks matter — see below).
- Build a flavor: `./gradlew assembleStandardRelease` (or `assembleLudashiRelease` /
  `assemblePubgRelease`; swap `Release`→`Debug` for a debug build). CI (`.github/workflows/main.yml`)
  builds `assembleRelease` (all flavors) with NDK `26.1.10909125` / `cmake 3.22.1`.
- `preBuild` auto-downloads large prebuilt assets on first build: `downloadImageFS`
  (`imagefs.tar.zst`) and `downloadProton` (`proton-9.0-arm64ec.tar.zst`) into
  `app/src/main/assets/`. These are `.tar.zst` blobs, gitignored, fetched from
  `star-emu/contents`.
- **Building needs Steam jars that are not committed.** `app/libs/steam/*.jar` (javasteam,
  protobuf-java, commons-lang3, ktor/kotlinx-serialization deps for DepotDownloader) must be
  built from `joshuatam/JavaSteam` (branch `gamenative-latest`) — see the "Build JavaSteam"
  step in `.github/workflows/main.yml` for the exact patch/build/copy recipe. Without these,
  the build fails to resolve Steam-related classes.
- Native code is built via CMake/NDK (`app/src/main/cpp/...`), `abiFilters 'arm64-v8a'` only.
  Two native components are git submodules — `app/src/main/cpp/bionic-fg` and
  `app/src/main/cpp/vkbasalt` — run `git submodule update --init --recursive` if they're empty.
  There are dedicated CI workflows (`build-bionic-fg.yml`, `build-vkbasalt.yml`) for rebuilding
  those native layers independently.
- Signing: both debug and release use a fixed, publicly-known **AOSP testkey**
  (`keystore/testkey.p12`), so every build shares one signature and new builds install over
  old ones without an uninstall. Release has `crunchPngs false` (several `.png`-named assets
  are actually GIFs and AAPT2's crunch step rejects them) and `lint { abortOnError false }`.
  `minifyEnabled` is `false` in both build types (no R8).
- On-device install/testing loop: `adb install -r app/build/outputs/apk/<flavor>/release/app-<flavor>-release.apk`.

## Architecture

### The emulation stack (native side, `app/src/main/cpp/`)
Windows PE binaries run under **Wine** on **Box64/Box86** (x86→ARM64 translation) or
**WOWBox64**/**arm64ec** for arm64ec containers, with **FEXCore** as an alternate translation
backend. Graphics go **DXVK** (D3D8/9/10/11→Vulkan) or **VKD3D-Proton** (D3D12→Vulkan) through
**Turnip/Mesa** (open-source Adreno Vulkan/GL drivers, with adrenotools for hooking vendor
blobs) or **VirGL**. `app/src/main/cpp/winlator/` + `scanout/` + `asurfacerenderer/` implement
the host-side compositor (`libwinlator.so`) and the `SurfaceFlinger`/`ASurfaceRenderer` present
path. `app/src/main/cpp/proot/` provides the guest filesystem sandboxing.

### The X server (`xserver/` + `xenvironment/`, pure Java)
A from-scratch X11 server implementation (`XServer.java`, `WindowManager.java`,
`ClientOpcodes.java`, `requests/`, `extensions/`) that Wine's X11 driver talks to over a socket
(`xconnector/`). `xenvironment/` (`XEnvironment.java`, `ImageFs.java`, `ImageFsInstaller.java`,
`components/`) manages the guest root filesystem image (extracted from `imagefs.tar.zst`) and
process environment setup — this is where fork-local install-time fixes tend to live (e.g. the
Xiaomi libjpeg-shadow removal in `ImageFsInstaller`).

### Rendering pipeline (`renderer/`)
`HostRenderer` → `GLRenderer` / `NativeRenderer` / `ASurfaceRenderer` implement the three
selectable host renderers (OpenGL, Vulkan, VirGL-backed). `DirectScanout.java` is the
low-latency direct-present path (bypasses the compositor, mutually exclusive with
post-processing). `EffectComposer.java` + `effects/` implement the OpenGL/Vulkan screen effects
(FXAA, CRT, CAS, debanding, etc.) and spatial upscalers (SGSR/FSR/NIS). Frame generation is a
separate concern layered on top: **bionic-fg** and **lsfg-vk** are Vulkan layers (native,
submodule/asset-based) selected per-container and controlled live from the in-game drawer
(`ui/XServerDrawer.kt`); `Container.java` persists the selected engine, multiplier, and
flow-scale across launches. The standalone FPS limiter paces the guest by delaying the X11
Present extension's `IdleNotify`, independent of frame-gen engine.

### Containers (`container/`)
`Container.java` is the persisted per-container config model (Wine version, graphics driver,
DXVK/VKD3D version, Box64 preset, drive mappings, env vars, frame-gen state, ReShade loadout,
etc.); `ContainerManager.java` owns the container list and lifecycle; `Shortcut.java` is a
per-game shortcut that can override container settings. Container and shortcut editors are
Compose screens under `ui/screens/` (`ContainerDetailScreen.kt`, `ShortcutsScreen.kt` +
matching `*ViewModel.kt`) — no Hilt/Room here; state is plain ViewModels + `Container`'s own
`SharedPreferences`-backed persistence, not a database.

### UI layers (mixed Java/Kotlin — don't assume one style)
Older screens are Java `Activity`/`Fragment` (`MainActivity.kt` is the shell, but
`SettingsFragment.java`, `InputControlsFragment.java`, `ControlsEditorActivity.java`,
`XServerDisplayActivity.java` are Java). Newer feature work (containers, shortcuts, contents/
component downloads, ReShade, settings, GOG store, adreno driver picker) is Jetpack Compose
under `ui/screens/`, `ui/components/`, `ui/dialogs/`, `ui/overlays/`, `ui/theme/`. When editing,
match the language and pattern already used in that file/screen rather than introducing a new
one. The in-game side drawer (`ui/XServerDrawer.kt`) and its overlays are theme-aware — the
selected accent recolors both the app chrome and the in-game drawer, so new drawer UI should
pull colors from the theme system, not hardcode them.

### Store integrations (`store/`)
GOG store integration (sign-in, library, download, cloud saves) lives under `store/` with
`store/compat/` for compatibility shims. Steam support depends on the externally-built
`app/libs/steam/*.jar` (see Build section).

### ReShade / vkBasalt (`reshade/`, `app/src/main/cpp/vkbasalt/`)
Per-game ReShade `.fx` effects compile on-device through the bundled vkBasalt Vulkan layer
(git submodule). `reshade/` (Kotlin/Java) handles the catalog, per-game effect selection, and
generating typed controls (sliders/toggles/dropdowns/color pickers) from parsed shader
uniforms; `docs/reshade.json.example` documents the catalog manifest shape.

### Product flavors
Three flavors share one `namespace`/codebase, differentiated only by `applicationId` +
`app_name` in `app/build.gradle`'s `productFlavors` block: `standard` (`com.winlator.sky`,
"Skylator Bionic"), `ludashi` (`com.ludashi.benchmark`, disguised as a benchmark app), `pubg`
(`com.tencent.ig`, disguised as PUBG). Flavor-specific behavior beyond the manifest/identity is
rare — check for it before assuming a change is flavor-neutral.

## Repo-specific conventions

- **This is a personal fork; commit history carries fork-local patches on top of upstream.**
  When pulling a new upstream release, some of these get lost in the merge and need
  re-applying — see `.claude/skills/reapply-fork-fixes/SKILL.md` for the registry of known
  fork-local fixes (identity rename, Xiaomi libjpeg-shadow fix, in-game download-list refresh,
  HUD CPU/power fix, frame-gen persistence + flow presets) with detection commands and the
  exact commit SHA each one came from. Use `/reapply-fork-fixes` after any upstream merge.
- Diagnostic logs should be gated behind `BuildConfig.DEBUG`.
- New user-facing strings go in `res/values/strings.xml`; new preferences need both a `key` in
  `res/xml/` and a default in `res/values/`. Prefer existing dimens/theme values over raw
  literals in layouts.
- `.claude/agents/` has domain-specialist agent definitions (android-app-engineer,
  graphics-vulkan-engineer, native-steam-engineer, wine-compat-engineer,
  release-device-engineer, ama-agent) written for this project family — note some of their
  descriptions reference a **sibling app** (e.g. Hilt/Room, `PluviaApp`/`UnifiedActivity`) that
  is a *different* codebase in the same family, not this repo; this repo does not use Hilt or
  Room.
- Long-form design/investigation docs live in `docs/*.md` (e.g. `GL_DIRECT_SCANOUT_PLAN.md`,
  `SGSR_HDR_VULKAN_PLAN.md`) and root-level `*_REPORT.md`/`*_LOG.md`/`*_RUNBOOK.md` files —
  check these for prior art before re-investigating a graphics/frame-gen problem from scratch.
