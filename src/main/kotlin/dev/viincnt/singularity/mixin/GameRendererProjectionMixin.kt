package dev.viincnt.singularity.mixin

import dev.viincnt.singularity.render.DlssFrameTargets
import dev.viincnt.singularity.render.DlssTemporalState
import net.minecraft.client.renderer.GameRenderer
import org.joml.Matrix4f
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.injection.At
import org.spongepowered.asm.mixin.injection.ModifyArg

/** Applies temporal jitter only to the projection consumed by the world pass. */
@Mixin(GameRenderer::class)
abstract class GameRendererProjectionMixin {
    @ModifyArg(
        method = ["renderLevel"],
        at = At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/ProjectionMatrixBuffer;getBuffer(Lorg/joml/Matrix4f;)Lcom/mojang/blaze3d/buffers/GpuBufferSlice;",
            ordinal = 0,
        ),
        index = 0,
    )
    private fun singularityJitterWorldProjection(matrix: Matrix4f): Matrix4f {
        val resources = DlssFrameTargets.resourcesForEvaluation() ?: return matrix
        return DlssTemporalState.jitteredProjection(matrix, resources.inputWidth, resources.inputHeight)
    }
}
