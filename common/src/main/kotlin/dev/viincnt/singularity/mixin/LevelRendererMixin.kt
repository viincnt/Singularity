package dev.viincnt.singularity.mixin

import com.mojang.blaze3d.pipeline.RenderTarget
import dev.viincnt.singularity.render.DlssFrameTargets
import net.minecraft.client.renderer.GameRenderer
import net.minecraft.client.renderer.LevelRenderer
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.injection.At
import org.spongepowered.asm.mixin.injection.Redirect

/** Routes only the world renderer through Singularity's internal resolution. */
@Mixin(LevelRenderer::class)
abstract class LevelRendererMixin {
    @Redirect(
        method = ["render"],
        at = At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/GameRenderer;mainRenderTarget()Lcom/mojang/blaze3d/pipeline/RenderTarget;",
        ),
    )
    private fun singularityUseInternalWorldTarget(renderer: GameRenderer): RenderTarget =
        DlssFrameTargets.worldTargetOr(renderer.mainRenderTarget())
}
