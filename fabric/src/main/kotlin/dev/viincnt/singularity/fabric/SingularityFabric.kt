package dev.viincnt.singularity.fabric

import dev.viincnt.singularity.Singularity
import net.fabricmc.api.ClientModInitializer

/**
 * The actual after-world-render hook lives in [dev.viincnt.singularity.fabric.mixin.LevelRendererProbeMixin]:
 * Fabric's old `WorldRenderEvents.END` API doesn't exist for this Minecraft
 * version's rewritten render pipeline, so a Mixin stands in for it instead.
 */
class SingularityFabric : ClientModInitializer {
    override fun onInitializeClient() {
        Singularity.LOGGER.info("Singularity loaded on Fabric; Vulkan resource probing is enabled.")
    }
}
