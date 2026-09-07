package dev.viincnt.singularity.mixin

import com.mojang.blaze3d.systems.GpuDevice
import com.mojang.blaze3d.systems.GpuDeviceBackend
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.gen.Accessor

@Mixin(GpuDevice::class)
interface GpuDeviceAccessor {
    @Accessor("backend")
    fun `singularity$getBackend`(): GpuDeviceBackend
}
