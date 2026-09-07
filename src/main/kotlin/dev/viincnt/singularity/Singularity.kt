package dev.viincnt.singularity

import com.mojang.logging.LogUtils
import dev.viincnt.singularity.render.VulkanInteropProbe
import net.neoforged.api.distmarker.Dist
import net.neoforged.fml.common.Mod
import net.neoforged.neoforge.common.NeoForge

@Mod(value = Singularity.MOD_ID, dist = [Dist.CLIENT])
class Singularity {
    init {
        NeoForge.EVENT_BUS.addListener(VulkanInteropProbe::onAfterLevel)
        LOGGER.info("Singularity loaded on NeoForge; Vulkan resource probing is enabled.")
    }

    companion object {
        const val MOD_ID = "singularity"
        internal val LOGGER = LogUtils.getLogger()
    }
}
