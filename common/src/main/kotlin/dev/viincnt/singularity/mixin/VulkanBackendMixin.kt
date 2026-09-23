package dev.viincnt.singularity.mixin

import com.mojang.blaze3d.vulkan.VulkanBackend
import com.mojang.blaze3d.vulkan.init.VulkanFeature
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet
import org.lwjgl.vulkan.VkPhysicalDeviceVulkan12Features
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.injection.At
import org.spongepowered.asm.mixin.injection.ModifyArg

@Mixin(VulkanBackend::class)
abstract class VulkanBackendMixin {
    @ModifyArg(
        method = ["createDevice"],
        at = At(
            value = "INVOKE",
            target = "Ljava/util/HashSet;<init>(Ljava/util/Collection;)V",
        ),
        index = 0,
    )
    private fun singularityAddDeviceExtensions(original: Collection<String>): Collection<String> =
        buildSet {
            addAll(original)
            // VK_NVX_binary_import/VK_NVX_image_view_handle are NVIDIA-only,
            // required for NGX's DLSS interop. Requesting them unconditionally
            // makes Vulkan device creation itself fail with
            // VK_ERROR_EXTENSION_NOT_PRESENT on non-NVIDIA drivers, e.g.
            // MoltenVK on macOS - so only request them where DlssNative
            // actually runs (see DlssNative.initializeOnce's own OS gate).
            if (System.getProperty("os.name").lowercase().contains("windows")) {
                add("VK_NVX_binary_import")
                add("VK_NVX_image_view_handle")
            }
            // Standard Khronos extension MetalNative uses to export the
            // Vulkan device's MTLDevice/MTLCommandQueue and per-image
            // MTLTexture objects (see singularity_native_metal.mm) - the
            // non-deprecated replacement for MoltenVK's old VK_MVK_moltenvk
            // vendor functions, which MoltenVK itself now warns can crash.
            if (System.getProperty("os.name").lowercase().contains("mac")) {
                add("VK_EXT_metal_objects")
            }
            add("VK_KHR_buffer_device_address")
        }

    @ModifyArg(
        method = ["createDevice"],
        at = At(
            value = "INVOKE",
            target = "Lit/unimi/dsi/fastutil/objects/ObjectOpenHashSet;<init>(Ljava/util/Collection;)V",
        ),
        index = 0,
    )
    private fun singularityEnableDeviceFeatures(original: Collection<VulkanFeature>): Collection<VulkanFeature> =
        ObjectOpenHashSet(original).apply {
            add(
                VulkanFeature(
                    VulkanBackend.VK12_FEATURES_STRUCT,
                    "bufferDeviceAddress",
                    VkPhysicalDeviceVulkan12Features.BUFFERDEVICEADDRESS.toLong(),
                ),
            )
        }
}
