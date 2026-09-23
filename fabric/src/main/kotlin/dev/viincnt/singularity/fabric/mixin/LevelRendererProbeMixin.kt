package dev.viincnt.singularity.fabric.mixin

import dev.viincnt.singularity.render.VulkanInteropProbe
import net.minecraft.client.renderer.LevelRenderer
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.injection.At
import org.spongepowered.asm.mixin.injection.Inject
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo

/**
 * Fabric-only stand-in for NeoForge's `RenderLevelStageEvent.AfterLevel`.
 *
 * The Fabric Rendering API's old `WorldRenderEvents.END` hook doesn't exist
 * for this Minecraft version's rewritten (submit-based) render pipeline, so
 * this mirrors [dev.viincnt.singularity.mixin.LevelRendererMixin]'s own
 * target method directly: tail-injecting into the same `LevelRenderer.render`
 * call that mixin already redirects the color target for.
 */
@Mixin(LevelRenderer::class)
abstract class LevelRendererProbeMixin {
    @Inject(method = ["render"], at = [At("RETURN")])
    private fun singularityRunAfterLevel(ci: CallbackInfo) {
        VulkanInteropProbe.runAfterLevel()
    }
}
