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
            add("VK_NVX_binary_import")
            add("VK_NVX_image_view_handle")
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
