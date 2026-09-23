package dev.viincnt.singularity.neoforge

import dev.viincnt.singularity.Singularity
import dev.viincnt.singularity.render.VulkanInteropProbe
import net.neoforged.api.distmarker.Dist
import net.neoforged.fml.common.Mod
import net.neoforged.neoforge.client.event.RenderLevelStageEvent
import net.neoforged.neoforge.common.NeoForge

@Mod(value = Singularity.MOD_ID, dist = [Dist.CLIENT])
class SingularityNeoForge {
    init {
        NeoForge.EVENT_BUS.addListener<RenderLevelStageEvent.AfterLevel> { VulkanInteropProbe.runAfterLevel() }
        Singularity.LOGGER.info("Singularity loaded on NeoForge; Vulkan resource probing is enabled.")
    }
}
