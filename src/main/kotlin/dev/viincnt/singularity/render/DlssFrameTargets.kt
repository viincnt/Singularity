package dev.viincnt.singularity.render

import com.mojang.blaze3d.GpuFormat
import com.mojang.blaze3d.pipeline.RenderTarget
import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.textures.GpuTexture
import com.mojang.blaze3d.vulkan.VulkanGpuTextureView
import com.mojang.blaze3d.vulkan.VulkanGpuTexture
import dev.viincnt.singularity.Singularity
import dev.viincnt.singularity.ngx.DlssOptimalSettings
import org.joml.Vector4f

/**
 * Vulkan images required by a future DLSS evaluation.
 *
 * The level renderer is redirected to [Targets.worldTarget], using the input
 * resolution suggested by NGX. At the end of the world pass, the target is
 * composited into Minecraft's native output target before the GUI is drawn.
 */
object DlssFrameTargets {
    private var targets: Targets? = null
    private var worldTargetUsedThisFrame = false

    fun ensure(outputWidth: Int, outputHeight: Int, settings: DlssOptimalSettings): Targets? {
        val inputWidth = settings.optimalWidth ?: return null
        val inputHeight = settings.optimalHeight ?: return null
        targets?.takeIf {
            it.inputWidth == inputWidth && it.inputHeight == inputHeight &&
                it.outputWidth == outputWidth && it.outputHeight == outputHeight
        }?.let { return it }

        close()
        val gpu = RenderSystem.getDevice()
        val usage = GpuTexture.USAGE_COPY_SRC or GpuTexture.USAGE_COPY_DST or
            GpuTexture.USAGE_TEXTURE_BINDING or GpuTexture.USAGE_RENDER_ATTACHMENT
        val created = Targets(
            inputWidth = inputWidth,
            inputHeight = inputHeight,
            outputWidth = outputWidth,
            outputHeight = outputHeight,
            worldTarget = InternalWorldTarget(inputWidth, inputHeight).also { it.createBuffers(inputWidth, inputHeight) },
            motionVectors = gpu.createTexture("Singularity DLSS motion vectors", usage, GpuFormat.RG16_FLOAT, inputWidth, inputHeight, 1, 1) as VulkanGpuTexture,
            outputColor = gpu.createTexture("Singularity DLSS output color", usage or STORAGE_REQUEST, GpuFormat.RGBA16_FLOAT, outputWidth, outputHeight, 1, 1) as VulkanGpuTexture,
        )
        created.motionVectorView = gpu.createTextureView(created.motionVectors) as VulkanGpuTextureView
        created.outputColorView = gpu.createTextureView(created.outputColor) as VulkanGpuTextureView
        created.outputTarget = ExternalColorTarget(created.outputColor, requireNotNull(created.outputColorView), outputWidth, outputHeight)
        targets = created
        val worldColor = requireNotNull(created.worldTarget.colorTexture) as VulkanGpuTexture
        val worldDepth = requireNotNull(created.worldTarget.depthTexture) as VulkanGpuTexture
        Singularity.LOGGER.info(
            "Allocated DLSS frame targets: input={}x{} color=0x{} depth=0x{} motion=0x{}; output={}x{} color=0x{}",
            inputWidth, inputHeight,
            worldColor.vkImage().toString(16), worldDepth.vkImage().toString(16), created.motionVectors.vkImage().toString(16),
            outputWidth, outputHeight, created.outputColor.vkImage().toString(16),
        )
        return created
    }

    fun close() {
        targets?.close()
        targets = null
        worldTargetUsedThisFrame = false
    }

    /**
     * Initializes the vector field for a frame. Minecraft does not expose its
     * per-draw motion vectors yet, so zero is the intentional conservative
     * fallback until the world pass is redirected through Singularity.
     */
    fun beginFrame(): Targets? {
        val current = targets ?: return null
        RenderSystem.getDevice().createCommandEncoder().clearColorTexture(
            current.motionVectors,
            ZERO_MOTION,
        )
        return current
    }

    fun worldTargetOr(fallback: RenderTarget): RenderTarget {
        val current = targets ?: return fallback
        if (!worldTargetUsedThisFrame) {
            // LevelRenderer's built-in clear still addresses GameRenderer's
            // native target. Clear the redirected target here so no undefined
            // texture contents survive into the next world frame.
            RenderSystem.getDevice().createCommandEncoder().clearColorAndDepthTextures(
                requireNotNull(current.worldTarget.colorTexture),
                CLEAR_COLOR,
                requireNotNull(current.worldTarget.depthTexture),
                0.0,
            )
        }
        worldTargetUsedThisFrame = true
        return current.worldTarget
    }

    fun compositeTo(output: RenderTarget) {
        val current = targets ?: return
        if (!worldTargetUsedThisFrame) return
        worldTargetUsedThisFrame = false
        val color = output.colorTextureView ?: return
        val depth = output.depthTextureView ?: return
        (if (current.dlssOutputReady) requireNotNull(current.outputTarget) else current.worldTarget)
            .blitAndBlendToTexture(color, depth)
    }

    fun markDlssOutputReady(ready: Boolean) {
        targets?.dlssOutputReady = ready
    }

    fun resourcesForEvaluation(): EvaluationResources? {
        val current = targets ?: return null
        val color = current.worldTarget.colorTexture as? VulkanGpuTexture ?: return null
        val colorView = current.worldTarget.colorTextureView as? VulkanGpuTextureView ?: return null
        val depth = current.worldTarget.depthTexture as? VulkanGpuTexture ?: return null
        val depthView = current.worldTarget.depthTextureView as? VulkanGpuTextureView ?: return null
        val motionView = current.motionVectorView ?: return null
        val outputView = current.outputColorView ?: return null
        return EvaluationResources(
            color, colorView, depth, depthView, current.motionVectors, motionView, current.outputColor, outputView,
            current.inputWidth, current.inputHeight, current.outputWidth, current.outputHeight,
        )
    }

    private val ZERO_MOTION = Vector4f(0.0f, 0.0f, 0.0f, 0.0f)
    private val CLEAR_COLOR = Vector4f(0.0f, 0.0f, 0.0f, 0.0f)
    private const val STORAGE_REQUEST = 1 shl 5

    class Targets(
        val inputWidth: Int,
        val inputHeight: Int,
        val outputWidth: Int,
        val outputHeight: Int,
        val worldTarget: RenderTarget,
        val motionVectors: VulkanGpuTexture,
        val outputColor: VulkanGpuTexture,
    ) : AutoCloseable {
        var motionVectorView: VulkanGpuTextureView? = null
        var outputColorView: VulkanGpuTextureView? = null
        var outputTarget: RenderTarget? = null
        var dlssOutputReady = false

        override fun close() {
            worldTarget.destroyBuffers()
            outputColorView?.close()
            motionVectorView?.close()
            outputTarget = null
            outputColor.close()
            motionVectors.close()
        }
    }

    data class EvaluationResources(
        val color: VulkanGpuTexture,
        val colorView: VulkanGpuTextureView,
        val depth: VulkanGpuTexture,
        val depthView: VulkanGpuTextureView,
        val motion: VulkanGpuTexture,
        val motionView: VulkanGpuTextureView,
        val output: VulkanGpuTexture,
        val outputView: VulkanGpuTextureView,
        val inputWidth: Int,
        val inputHeight: Int,
        val outputWidth: Int,
        val outputHeight: Int,
    )

    private class InternalWorldTarget(width: Int, height: Int) : RenderTarget(
        "Singularity DLSS world target ${width}x$height",
        true,
        GpuFormat.RGBA8_UNORM,
    )

    private class ExternalColorTarget(
        texture: VulkanGpuTexture,
        view: VulkanGpuTextureView,
        width: Int,
        height: Int,
    ) : RenderTarget("Singularity DLSS output target", false, GpuFormat.RGBA16_FLOAT) {
        init {
            this.width = width
            this.height = height
            this.colorTexture = texture
            this.colorTextureView = view
        }
    }
}
