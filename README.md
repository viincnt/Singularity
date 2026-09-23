<div align="center">

# 🌌 Singularity

> Experimental temporal reconstruction for Minecraft Java Edition — NVIDIA DLSS on Windows, Apple MetalFX on macOS — on top of Mojang's native Vulkan renderer, for NeoForge and Fabric/Quilt.

<img alt="language" src="https://img.shields.io/badge/language-Kotlin-7F52FF?style=for-the-badge&logo=kotlin&logoColor=white&labelColor=1a1b26" />
<img alt="backend" src="https://img.shields.io/badge/backend-Vulkan-AC162C?style=for-the-badge&logo=vulkan&logoColor=white&labelColor=1a1b26" />
<img alt="upscaler-dlss" src="https://img.shields.io/badge/upscaler-DLSS%20SR-76B900?style=for-the-badge&logo=nvidia&logoColor=white&labelColor=1a1b26" />
<img alt="upscaler-metalfx" src="https://img.shields.io/badge/upscaler-MetalFX-000000?style=for-the-badge&logo=apple&logoColor=white&labelColor=1a1b26" />
<img alt="loaders" src="https://img.shields.io/badge/loaders-NeoForge%20%7C%20Fabric%20%7C%20Quilt-1a1b26?style=for-the-badge" />
<img alt="license" src="https://img.shields.io/badge/license-MPL--2.0-0078D4?style=for-the-badge&labelColor=1a1b26" />

</div>

---

## 📌 Overview

**Singularity** is an experimental client-side mod exploring hardware-accelerated temporal reconstruction in **Minecraft Java Edition**, for both **NeoForge** and **Fabric** (Quilt runs the Fabric build directly).

Both backends integrate directly with Mojang's native Vulkan renderer rather than replacing it. Singularity hooks into the existing rendering pipeline after the world has been rendered and before the GUI is drawn — at that point the scene's native Vulkan resources (device, graphics queue, command submission, color buffer, depth buffer) are still available for external GPU work.

- On **Windows**, that Vulkan device talks to **NVIDIA NGX**, driving **DLSS Super Resolution**.
- On **macOS**, Minecraft's Vulkan backend runs through **MoltenVK**, so the same Vulkan handles are, under the hood, backed by real Metal objects. Singularity resolves those directly and drives **Apple MetalFX** Temporal Upscaling — no separate Metal renderer or OpenGL/IOSurface interop needed.

The JVM side is written entirely in **Kotlin**, shared between loaders in a `common` module. Two small native bridges (C++/NGX for Windows, Objective-C++/MetalFX for macOS) connect Minecraft's Vulkan resources to each platform's upscaler.

### ⚠️ Current limitation

**Neither backend renders the upscaled frame yet.**

The current prototype successfully initializes NVIDIA NGX and Apple MetalFX against Minecraft's real Vulkan device, detects upscaler support, and creates the reconstruction feature. Per-frame evaluation runs and is diagnostic-logged, but compositing the reconstructed output back into Minecraft's frame — replacing the fallback native-resolution path — is still under development.

On the current Windows development system, an **NVIDIA GeForce RTX 3050** reports:

```text
initialized=true
available=1
needsUpdatedDriver=0
supportFlags=0
```

In other words: **Minecraft and each platform's upscaler SDK are already talking to each other.** The remaining work is turning that integration into an actual temporal reconstruction pipeline.

---

## ⚙️ Architecture

Singularity sits between world rendering and GUI composition:

```text
Minecraft world rendering
          │
          ▼
 Mojang Vulkan renderer  ── on macOS, running through MoltenVK
          │
          ├── scene color
          ├── scene depth
          ├── motion vectors   ← planned
          ├── camera jitter    ← planned
          └── exposure         ← planned
          │
          ▼
      Singularity
          │
     ┌────┴────┐
     ▼         ▼
NVIDIA NGX   MoltenVK → Metal
     │         │
DLSS SR    MetalFX Temporal Upscaling
     │         │
     └────┬────┘
          ▼
   GUI composition
          │
          ▼
        Present
```

The reconstructed image is intended to replace only the rendered **3D scene**. Minecraft's HUD and other GUI elements remain at native output resolution and are composed afterwards.

This separation also allows Singularity to remain focused on temporal reconstruction rather than becoming a replacement renderer or shader pipeline.

---

## 🔬 Current state

The prototype currently has three integration paths verified on the Windows/NVIDIA development machine, plus a fourth (MetalFX) implemented against the same Vulkan/MoltenVK handles but not yet run on real Apple hardware.

### Vulkan frame access

Singularity hooks into Minecraft after world rendering and obtains the real Vulkan resources associated with the frame, including:

- Vulkan device
- graphics queue and queue family
- scene color image and image view
- scene depth image and image view
- access to Mojang's frame submission path

### Native GPU execution

The native bridge can record and submit Vulkan work alongside Minecraft's own rendering.

As a validation test, the native-pass probe:

1. creates a private `4×4` Vulkan image;
2. records a command buffer that clears it;
3. submits the work inside Mojang's frame submission;
4. copies the result into a readback buffer;
5. validates the resulting pixel on the CPU.

This verifies that Singularity is not merely observing Vulkan handles — native code can execute real GPU work within Minecraft's rendering lifecycle.

### NVIDIA NGX

The native bridge initializes NVIDIA NGX using Minecraft's Vulkan device and queries DLSS Super Resolution capability.

The NGX capability probe identified the extensions required for DLSS Super Resolution. Singularity enables them during Minecraft's Vulkan instance and device creation.

The current requirements are:

```text
VK_KHR_get_physical_device_properties2
VK_NVX_binary_import
VK_NVX_image_view_handle
VK_KHR_buffer_device_address
VK_KHR_push_descriptor
```

The Vulkan 1.2 `bufferDeviceAddress` feature is enabled as well.

On the development RTX 3050, both NGX initialization and DLSS capability detection succeed.

### Apple MetalFX

Minecraft has no native Metal backend, so Singularity does not switch renderers on macOS: it keeps using Mojang's Vulkan backend, which on macOS runs through **MoltenVK** (Vulkan → Metal translation). The `MetalNative` bridge resolves the underlying `id<MTLDevice>`/`id<MTLTexture>`/`id<MTLCommandBuffer>` straight out of the same Vulkan handles the DLSS bridge uses, via MoltenVK's vendor extension functions, and drives an `MTLFXTemporalScaler` with them — no OpenGL/IOSurface interop required.

The MetalFX bridge is at the same maturity as the DLSS one: it initializes, probes capability, creates the temporal scaler, and submits per-frame evaluation, all diagnostic-logged. It is not composited into the final image yet either.

---

## 🎯 Goals

The immediate goal is a correct reconstruction pipeline on **each** platform's own upscaler — DLSS Super Resolution on Windows, MetalFX Temporal Upscaling on macOS — rather than simply getting a native feature to execute.

Singularity needs to:

- render Minecraft's 3D world below display resolution;
- provide scene color and depth to the reconstruction backend;
- generate correct per-pixel motion vectors;
- implement camera jitter;
- provide exposure data where required;
- evaluate reconstruction once per rendered frame;
- composite the reconstructed scene before Minecraft draws the HUD;
- correctly recreate resources after resize, fullscreen changes, and world transitions;
- degrade cleanly when a reconstruction backend or required hardware is unavailable;
- keep that pipeline identical for players on NeoForge, Fabric, or Quilt.

Longer term, Singularity is intended to provide a common integration point for temporal reconstruction technologies without tying the rest of the rendering pipeline to a specific vendor or loader.

DLSS Super Resolution and MetalFX Temporal Upscaling are the first two implementations.

---

## 🗺️ Roadmap

### NVIDIA DLSS

- [x] NeoForge 26.2 project and Kotlin toolchain
- [x] Detect Minecraft's Vulkan backend
- [x] Capture native Vulkan frame resources
- [x] Execute native Vulkan commands inside Minecraft's frame submission
- [x] Validate GPU execution through CPU readback
- [x] Initialize NVIDIA NGX
- [x] Detect DLSS Super Resolution support
- [x] Enable NGX-required Vulkan extensions and device features
- [ ] Create persistent reconstruction resources
- [ ] Create the DLSS Super Resolution feature
- [ ] Add configurable internal render resolution
- [ ] Add DLSS quality modes
- [ ] Implement camera jitter
- [ ] Generate motion vectors
- [ ] Evaluate DLSS every frame
- [ ] Composite the reconstructed scene before the HUD
- [ ] Handle resize and resource recreation

### Apple MetalFX

- [x] Resolve Metal objects from Minecraft's MoltenVK-backed Vulkan handles
- [x] Initialize MetalFX and detect `MTLFXTemporalScaler` support
- [x] Create the MetalFX temporal scaler
- [ ] Evaluate MetalFX every frame against real (non-zero) motion vectors
- [ ] Composite the reconstructed scene before the HUD

### Multi-loader

- [x] Split into `common` / `neoforge` / `fabric` Gradle modules
- [x] Fabric entry point and `fabric.mod.json`
- [ ] Jar-in-Jar verification for both loaders' packaged builds
- [ ] Quilt smoke test (should run the Fabric jar unmodified)

### Integration

- [ ] In-game configuration
- [ ] Runtime reconstruction mode switching
- [ ] Fullscreen and world-switch handling
- [ ] Normal packaging for both loaders
- [ ] Shader-pipeline compatibility
- [ ] Aperture integration

### Future backends

Singularity is designed so that temporal reconstruction does not have to remain tied to a single implementation.

Additional backends may be explored where appropriate and where development hardware is available.

**MetalFX** on Apple Silicon is now probed at the same level DLSS is (see above); it is not composited into the frame yet.

No other backend is currently implemented or promised.

---

## 🧱 Multi-loader

Singularity is split into three Gradle modules:

- `common/` — everything loader-agnostic: the Vulkan bootstrap Mixins, the DLSS and MetalFX native bridges, and the frame/temporal-state integration. Compiled with Fabric Loom against official Mojang mappings.
- `neoforge/` — the NeoForge entry point (`SingularityNeoForge`) and `neoforge.mods.toml`.
- `fabric/` — the Fabric entry point (`SingularityFabric`) and `fabric.mod.json`. Quilt runs Fabric mods directly, so no separate Quilt module is needed.

Both loader modules compile against official Mojang mappings too, so `common`'s Mixins target identical class/method descriptors on either loader without remapping.

---

## 🧩 Aperture

Once the Iris team's Vulkan-native **Aperture** renderer becomes suitable for integration, the goal is for Singularity to operate as a compatible temporal-reconstruction layer rather than replacing its shader pipeline.

The intended architecture is roughly:

```text
Minecraft
    │
    ▼
Mojang Vulkan renderer
    │
    ▼
Aperture
    │
    ├── shader pack
    ├── scene color
    ├── depth
    └── temporal data
          │
          ▼
      Singularity
          │
          ▼
 Temporal backend
          │
          ▼
  DLSS SR / MetalFX
```

This would allow shader packs and hardware-accelerated temporal reconstruction to coexist within the same rendering pipeline.

---

## 🧪 Development environment

The current prototype is developed against:

| Component              | Version / implementation     |
| ---------------------- | ---------------------------- |
| Minecraft              | `26.2`                       |
| NeoForge               | `26.2.0.77`                  |
| Fabric Loader          | `0.19.5`                     |
| Fabric API             | `0.161.0+26.2`                |
| Fabric Loom            | `1.18.2`                     |
| Kotlin                 | `2.4.0`                      |
| Kotlin for Forge       | `6.3.0`                      |
| Fabric Language Kotlin | `1.14.1+kotlin.2.4.20`        |
| Java toolchain         | JDK `25`                     |
| Native compiler        | Visual Studio 2022 C++ x64 (Windows), clang++ (macOS) |
| Graphics API           | Vulkan (via MoltenVK on macOS)                        |
| Reconstruction backend | NVIDIA DLSS Super Resolution, Apple MetalFX            |
| Integration            | NVIDIA NGX Vulkan SDK, MetalFX + MoltenVK              |
| Development GPU        | NVIDIA GeForce RTX 3050                                |

### `scripts/singularity.sh` / `scripts/singularity.bat`

A small dev CLI (bash on macOS/Linux, batch on Windows — no other runtime required) wraps the two steps below:

```bash
./scripts/singularity.sh vendor              # populate third_party/vulkan-headers and third_party/dlss
./scripts/singularity.sh run                 # detect the OS, check vendoring, launch :neoforge:runClient
./scripts/singularity.sh run --loader fabric # or the Fabric loader
```

```powershell
scripts\singularity.bat vendor
scripts\singularity.bat run
scripts\singularity.bat run --loader fabric
```

`vendor` clones [Vulkan-Headers](https://github.com/KhronosGroup/Vulkan-Headers) and [NVIDIA's DLSS SDK](https://github.com/NVIDIA/DLSS) (both public, no login required) into `third_party/`, stripped of their own `.git` so they're plain vendored trees, not nested repos — skips already-populated directories unless you pass `--force`. Neither is committed (see `.gitignore`); every machine vendors its own copy.

`run` picks the native bridge for the current OS (MetalFX needs only `vulkan-headers` on macOS; DLSS needs both `vulkan-headers` and `dlss` on Windows) and fails fast with what's missing instead of letting `runClient` hit a confusing native build error.

### Manual equivalent

```bash
./gradlew --gradle-user-home .gradle-user-home build   # both neoforge and fabric jars; use :neoforge:build / :fabric:build for one
./native/build-native-macos.sh                          # or .\native\build-native.bat on Windows
./gradlew :neoforge:runClient                            # or :fabric:runClient
```

`gradlew.bat` is only for Windows PowerShell/cmd; on macOS/Linux always use `./gradlew` (no `.bat`).

The `runClient` development profiles currently:

- force Minecraft's Vulkan backend;
- build the platform's native bridge (DLSS on Windows, MetalFX on macOS);
- launch the existing `New World` development world automatically (NeoForge run).

### Installation status

Normal installations currently require **Kotlin for Forge 6.3.x** (NeoForge) or **Fabric Language Kotlin 1.14.x** (Fabric/Quilt) as a separate dependency mod.

Packaging and extraction of the native bridge and feature library (NVIDIA NGX on Windows, MetalFX on macOS) for normal installations are not finished yet.

---

## 📁 Project structure

```text
Singularity/
├── common/src/main/
│   ├── kotlin/dev/viincnt/singularity/
│   │   ├── mixin/                  Vulkan bootstrap Mixins
│   │   ├── ngx/                    Kotlin-to-NGX boundary
│   │   ├── metal/                  Kotlin-to-MetalFX boundary
│   │   ├── render/                 Frame and command-buffer integration
│   │   └── Singularity.kt          Shared logger/state (no loader deps)
│   └── resources/                  Mixin metadata
│
├── neoforge/src/main/
│   ├── kotlin/.../neoforge/        NeoForge entry point
│   └── templates/META-INF/         NeoForge mod metadata template
│
├── fabric/src/main/
│   ├── kotlin/.../fabric/          Fabric entry point
│   └── resources/fabric.mod.json   Fabric mod metadata
│
├── native/
│   ├── src/main/cpp/               Native NVIDIA NGX bridge (Windows)
│   ├── src/main/objcpp/            Native MetalFX bridge (macOS)
│   ├── build/                      Generated native binaries
│   ├── build-native.bat            Windows native build entry point
│   └── build-native-macos.sh       macOS native build entry point
│
├── third_party/
│   ├── dlss/                       NVIDIA DLSS SDK
│   └── vulkan-headers/             Khronos Vulkan headers
│
├── settings.gradle
├── build.gradle
├── gradle.properties
└── LICENSE
```

---

## 🖥️ Compatibility

The current prototype targets:

- Minecraft Java Edition `26.2`
- NeoForge or Fabric (Quilt runs the Fabric jar directly)
- Mojang's native Vulkan renderer (via MoltenVK on macOS)
- Windows with an NVIDIA RTX GPU (DLSS Super Resolution)
- macOS with a MetalFX-capable GPU (MetalFX Temporal Upscaling)

Singularity is entirely client-side.

Unsupported hardware or unavailable reconstruction backends should eventually result in a clean fallback to Minecraft's normal rendering path rather than preventing the game from running.

Linux is not currently targeted by either native bridge.

---

## 📚 References

- [NeoForge 26.2 MDK](https://github.com/NeoForgeMDKs/MDK-26.2-ModDevGradle)
- [Kotlin for Forge](https://github.com/thedarkcolour/KotlinForForge/tree/6.x)
- [NVIDIA DLSS SDK](https://github.com/NVIDIA/DLSS)
- [Khronos Vulkan Headers](https://github.com/KhronosGroup/Vulkan-Headers)
- [Apple MetalFX](https://developer.apple.com/documentation/metalfx)
- [MoltenVK](https://github.com/KhronosGroup/MoltenVK)
- [Fabric Loom](https://github.com/FabricMC/fabric-loom)

---

## 📄 License

Singularity is licensed under the [Mozilla Public License 2.0](LICENSE).

You are free to use, modify, and distribute the project under the terms of the MPL-2.0. Modifications to MPL-licensed files must remain available under the MPL-2.0 when distributed, while Singularity may still be combined with code under other licenses.

Third-party components retain their respective licenses and are not covered by the MPL-2.0.

In particular, NVIDIA DLSS SDK files and binaries under `third_party/dlss` remain subject to NVIDIA's applicable license terms and are **not** relicensed under the MPL-2.0.
