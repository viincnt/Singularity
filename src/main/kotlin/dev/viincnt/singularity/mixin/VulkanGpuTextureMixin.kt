package dev.viincnt.singularity.mixin

import com.mojang.blaze3d.GpuFormat
import com.mojang.blaze3d.vulkan.VulkanConst
import com.mojang.blaze3d.vulkan.VulkanGpuTexture
import org.lwjgl.vulkan.VK12
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.injection.At
import org.spongepowered.asm.mixin.injection.Redirect

/** Adds storage access only to the image written by NVIDIA's DLSS compute pass. */
@Mixin(VulkanGpuTexture::class)
abstract class VulkanGpuTextureMixin {
    @Redirect(
        method = ["<init>"],
        at = At(
            value = "INVOKE",
            target = "Lcom/mojang/blaze3d/vulkan/VulkanConst;textureUsageToVk(ILcom/mojang/blaze3d/GpuFormat;)I",
        ),
    )
    private fun singularityAddStorageUsage(usage: Int, format: GpuFormat): Int {
        val vkUsage = VulkanConst.textureUsageToVk(usage, format)
        return if ((usage and (1 shl 5)) != 0) {
            vkUsage or VK12.VK_IMAGE_USAGE_STORAGE_BIT
        } else {
            vkUsage
        }
    }

}
