package dev.viincnt.singularity.mixin

import com.mojang.blaze3d.vulkan.VulkanDebug
import com.mojang.blaze3d.vulkan.VulkanInstance
import org.spongepowered.asm.mixin.Final
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.Shadow
import org.spongepowered.asm.mixin.injection.At
import org.spongepowered.asm.mixin.injection.Inject
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo

@Mixin(VulkanInstance::class)
abstract class VulkanInstanceMixin {
    @Shadow
    @Final
    private lateinit var enabledExtensions: MutableSet<String>

    @Inject(
        method = ["<init>"],
        at = [At(
            value = "INVOKE",
            target = "Lcom/mojang/blaze3d/vulkan/VulkanDebug;create(IZLjava/util/Set;Ljava/util/Set;)Lcom/mojang/blaze3d/vulkan/VulkanDebug;",
            shift = At.Shift.BEFORE,
        )],
    )
    private fun singularityAddInstanceExtensions(
        debugVerbosity: Int,
        wantsDebugLabels: Boolean,
        validation: Boolean,
        callback: CallbackInfo,
    ) {
        enabledExtensions.add("VK_KHR_get_physical_device_properties2")
    }
}
