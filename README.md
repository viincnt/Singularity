<div align="center">

# 🌌 Singularity

> Experimental temporal reconstruction for Minecraft Java Edition with NVIDIA DLSS Super Resolution on Mojang's native Vulkan renderer.

<img alt="language" src="https://img.shields.io/badge/language-Kotlin-7F52FF?style=for-the-badge&logo=kotlin&logoColor=white&labelColor=1a1b26" />
<img alt="backend" src="https://img.shields.io/badge/backend-Vulkan-AC162C?style=for-the-badge&logo=vulkan&logoColor=white&labelColor=1a1b26" />
<img alt="upscaler" src="https://img.shields.io/badge/upscaler-DLSS%20SR-76B900?style=for-the-badge&logo=nvidia&logoColor=white&labelColor=1a1b26" />
<img alt="license" src="https://img.shields.io/badge/license-MPL--2.0-0078D4?style=for-the-badge&labelColor=1a1b26" />

</div>

---

## 📌 Overview

**Singularity** is an experimental client-side NeoForge mod exploring hardware-accelerated temporal reconstruction in **Minecraft Java Edition**.

The first backend integrates **NVIDIA DLSS Super Resolution** directly with Mojang's native Vulkan renderer. Rather than replacing Minecraft's renderer, Singularity hooks into the existing rendering pipeline after the world has been rendered and before the GUI is drawn.

At that point, the scene's native Vulkan resources are still available, allowing Singularity to access the device, graphics queue, command submission, color buffer, and depth buffer required for external GPU work.

The JVM side is written entirely in **Kotlin**. A small native bridge connects Minecraft's Vulkan resources to NVIDIA's NGX SDK.

### ⚠️ Current limitation

**DLSS does not render frames yet.**

The current prototype successfully initializes NVIDIA NGX against Minecraft's real Vulkan device and detects DLSS Super Resolution support. Feature creation, temporal inputs, internal resolution scaling, and per-frame DLSS evaluation are still under development.

On the current development system, an **NVIDIA GeForce RTX 3050** reports:

```text
initialized=true
available=1
needsUpdatedDriver=0
supportFlags=0
```

In other words: **Minecraft and NVIDIA NGX are already talking to each other.** The remaining work is turning that integration into an actual temporal reconstruction pipeline.

---

## ⚙️ Architecture

Singularity sits between world rendering and GUI composition:

```text
Minecraft world rendering
          │
          ▼
 Mojang Vulkan renderer
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
          ▼
    Temporal backend
          │
          ▼
    NVIDIA DLSS SR
          │
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

The prototype currently has three independently verified integration paths.

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

---

## 🎯 Goals

The immediate goal is a correct **DLSS Super Resolution** implementation rather than simply getting an NGX feature to execute.

Singularity needs to:

- render Minecraft's 3D world below display resolution;
- provide scene color and depth to the reconstruction backend;
- generate correct per-pixel motion vectors;
- implement camera jitter;
- provide exposure data where required;
- evaluate reconstruction once per rendered frame;
- composite the reconstructed scene before Minecraft draws the HUD;
- correctly recreate resources after resize, fullscreen changes, and world transitions;
- degrade cleanly when a reconstruction backend or required hardware is unavailable.

Longer term, Singularity is intended to provide a common integration point for temporal reconstruction technologies without tying the rest of the rendering pipeline to a specific vendor.

DLSS Super Resolution is the first implementation.

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

### Integration

- [ ] In-game configuration
- [ ] Runtime reconstruction mode switching
- [ ] Fullscreen and world-switch handling
- [ ] Normal NeoForge native-library packaging
- [ ] Shader-pipeline compatibility
- [ ] Aperture integration

### Future backends

Singularity is designed so that temporal reconstruction does not have to remain tied to a single implementation.

Additional backends may be explored where appropriate and where development hardware is available.

**MetalFX** on Apple Silicon is a potential future target.

No other backend is currently implemented or promised.

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
       DLSS SR
```

This would allow shader packs and hardware-accelerated temporal reconstruction to coexist within the same rendering pipeline.

---

## 🧪 Development environment

The current prototype is developed against:

| Component              | Version / implementation     |
| ---------------------- | ---------------------------- |
| Minecraft              | `26.2`                       |
| NeoForge               | `26.2.0.77`                  |
| Kotlin                 | `2.4.0`                      |
| Kotlin for Forge       | `6.3.0`                      |
| Java toolchain         | JDK `25`                     |
| Native compiler        | Visual Studio 2022 C++ x64   |
| Graphics API           | Vulkan                       |
| Reconstruction backend | NVIDIA DLSS Super Resolution |
| Integration            | NVIDIA NGX Vulkan SDK        |
| Development GPU        | NVIDIA GeForce RTX 3050      |

### Build the mod

```powershell
.\gradlew.bat --gradle-user-home .gradle-user-home build
```

### Build the native bridge

```powershell
.\native\build-native.bat
```

### Run the development client

```powershell
.\gradlew.bat --gradle-user-home .gradle-user-home runClient -x createMinecraftArtifacts
```

The `runClient` development profile currently:

- forces Minecraft's Vulkan backend;
- builds the native bridge;
- copies the required `nvngx_dlss.dll`;
- launches the existing `New World` development world automatically.

### Installation status

Normal installations currently require **Kotlin for Forge 6.3.x**.

Packaging and extraction of the native bridge and NVIDIA feature library for normal NeoForge installations are not finished yet.

---

## 📁 Project structure

```text
Singularity/
├── src/main/
│   ├── kotlin/dev/viincnt/singularity/
│   │   ├── mixin/                  Vulkan bootstrap Mixins
│   │   ├── ngx/                    Kotlin-to-NGX boundary
│   │   ├── render/                 Frame and command-buffer integration
│   │   └── Singularity.kt          NeoForge entry point
│   ├── resources/                  Mixin metadata
│   └── templates/META-INF/         NeoForge mod metadata template
│
├── native/
│   ├── src/main/cpp/               Native NVIDIA NGX bridge
│   ├── build/                      Generated native binaries
│   └── build-native.bat            Windows native build entry point
│
├── third_party/
│   ├── dlss/                       NVIDIA DLSS SDK
│   └── vulkan-headers/             Khronos Vulkan headers
│
├── build.gradle
├── gradle.properties
└── LICENSE
```

---

## 🖥️ Compatibility

The current DLSS prototype targets:

- Minecraft Java Edition `26.2`
- NeoForge
- Mojang's native Vulkan renderer
- Windows
- NVIDIA RTX GPUs with DLSS Super Resolution support

Singularity is entirely client-side.

Unsupported hardware or unavailable reconstruction backends should eventually result in a clean fallback to Minecraft's normal rendering path rather than preventing the game from running.

macOS support is not currently implemented. A native **MetalFX** backend for Apple Silicon may be explored later.

---

## 📚 References

- [NeoForge 26.2 MDK](https://github.com/NeoForgeMDKs/MDK-26.2-ModDevGradle)
- [Kotlin for Forge](https://github.com/thedarkcolour/KotlinForForge/tree/6.x)
- [NVIDIA DLSS SDK](https://github.com/NVIDIA/DLSS)
- [Khronos Vulkan Headers](https://github.com/KhronosGroup/Vulkan-Headers)

---

## 📄 License

Singularity is licensed under the [Mozilla Public License 2.0](LICENSE).

You are free to use, modify, and distribute the project under the terms of the MPL-2.0. Modifications to MPL-licensed files must remain available under the MPL-2.0 when distributed, while Singularity may still be combined with code under other licenses.

Third-party components retain their respective licenses and are not covered by the MPL-2.0.

In particular, NVIDIA DLSS SDK files and binaries under `third_party/dlss` remain subject to NVIDIA's applicable license terms and are **not** relicensed under the MPL-2.0.
