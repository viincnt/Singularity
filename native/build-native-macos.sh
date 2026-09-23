#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/.."

: "${JAVA_HOME:?Set JAVA_HOME to a JDK 25 install before running this script}"
mkdir -p native/build

# -undefined dynamic_lookup: vkGetInstanceProcAddr isn't linked against a
# specific Vulkan loader here - by the time this bridge is dlopen'd via
# System.load, Minecraft's own LWJGL Vulkan bindings have already loaded a
# Vulkan loader (MoltenVK) into the process, which is where this resolves.
clang++ -std=c++20 -x objective-c++ -fobjc-arc -dynamiclib \
  -undefined dynamic_lookup \
  -I"$JAVA_HOME/include" \
  -I"$JAVA_HOME/include/darwin" \
  -I"third_party/vulkan-headers/include" \
  -framework Metal \
  -framework MetalFX \
  -framework Foundation \
  -o native/build/libsingularity_native_metal.dylib \
  native/src/main/objcpp/singularity_native_metal.mm
