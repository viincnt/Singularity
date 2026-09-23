package dev.viincnt.singularity.render

import com.mojang.blaze3d.pipeline.RenderTarget
import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.vulkan.VulkanDevice
import com.mojang.blaze3d.vulkan.VulkanGpuTexture
import com.mojang.blaze3d.vulkan.VulkanGpuTextureView
import dev.viincnt.singularity.Singularity
import dev.viincnt.singularity.metal.MetalNative
import dev.viincnt.singularity.mixin.GpuDeviceAccessor
import dev.viincnt.singularity.ngx.DlssNative
import net.minecraft.client.Minecraft

/**
 * Discovers the native resources that a DLSS/MetalFX implementation will consume.
 *
 * Called by each loader's entrypoint right after world rendering completes and
 * before the GUI is drawn: the main depth texture still contains scene depth,
 * so this is the useful boundary regardless of which mod-loading event fires it.
 */
object VulkanInteropProbe {
    private var lastSnapshot: VulkanFrameResources? = null
    private var reportedNonVulkanBackend = false

    fun runAfterLevel() {
        val deviceInfo = RenderSystem.getDevice().deviceInfo
        val backend = (RenderSystem.getDevice() as GpuDeviceAccessor).`singularity$getBackend`()
        val target = Minecraft.getInstance().gameRenderer.mainRenderTarget()
        val colorTexture = target.colorTexture
        val depthTexture = target.depthTexture
        val colorView = target.colorTextureView
        val depthView = target.depthTextureView

        if (
            backend !is VulkanDevice ||
            colorTexture !is VulkanGpuTexture ||
            depthTexture !is VulkanGpuTexture ||
            colorView !is VulkanGpuTextureView ||
            depthView !is VulkanGpuTextureView
        ) {
            if (!reportedNonVulkanBackend) {
                reportedNonVulkanBackend = true
                Singularity.LOGGER.warn(
                    "DLSS probe inactive: backend={} device={} (enable Minecraft's Vulkan backend)",
                    deviceInfo.backendName,
                    deviceInfo.name,
                )
            }
            return
        }

        reportedNonVulkanBackend = false
        val snapshot = target.toVulkanResources(
            backendName = deviceInfo.backendName,
            deviceName = deviceInfo.name,
            deviceHandle = backend.vkDevice().address(),
            graphicsQueueHandle = backend.graphicsQueue().vkQueue().address(),
            graphicsQueueFamily = backend.graphicsQueue().queueFamilyIndex(),
            colorImage = colorTexture.vkImage(),
            colorImageView = colorView.vkImageView(),
            depthImage = depthTexture.vkImage(),
            depthImageView = depthView.vkImageView(),
        )

        if (snapshot != lastSnapshot) {
            lastSnapshot = snapshot
            Singularity.LOGGER.info(
                "Vulkan frame resources ready: backend={}, device={}, size={}x{}, " +
                    "deviceHandle=0x{}, graphicsQueue=0x{} (family={}), " +
                    "colorFormat={}, colorImage=0x{}, colorView=0x{}, " +
                    "depthFormat={}, depthImage=0x{}, depthView=0x{}",
                snapshot.backendName,
                snapshot.deviceName,
                snapshot.width,
                snapshot.height,
                snapshot.deviceHandle.toString(16),
                snapshot.graphicsQueueHandle.toString(16),
                snapshot.graphicsQueueFamily,
                snapshot.colorFormat,
                snapshot.colorImage.toString(16),
                snapshot.colorImageView.toString(16),
                snapshot.depthFormat,
                snapshot.depthImage.toString(16),
                snapshot.depthImageView.toString(16),
            )
        }

        NativeVulkanPassProbe.runOnce(backend)
        DlssNative.initializeOnce(backend)
        MetalNative.initializeOnce(backend)
        DlssNative.logOptimalSettingsFor(snapshot.width, snapshot.height)
        MetalNative.logOptimalSettingsFor(snapshot.width, snapshot.height)
        val recommendation = DlssNative.qualityRecommendation(snapshot.width, snapshot.height)
            ?: MetalNative.qualityRecommendation(snapshot.width, snapshot.height)
        recommendation?.let {
            DlssFrameTargets.ensure(snapshot.width, snapshot.height, it)
            DlssFrameTargets.beginFrame()
            DlssTemporalState.beginFrame(Minecraft.getInstance().gameRenderer.mainCamera())
        }
        DlssNative.createFeatureOnce(backend, snapshot.width, snapshot.height)
        MetalNative.createFeatureOnce(snapshot.width, snapshot.height)
        val dlssOutputReady = DlssNative.evaluate(backend)
        // MetalNative.evaluate() submits to its own MTLCommandQueue, entirely
        // outside Minecraft's Vulkan submission/synchronization - there's no
        // fence/semaphore ordering its write to the output texture against
        // Minecraft's own reads of it yet (VK_EXT_metal_objects can export a
        // shared event for this; not wired up). Run it so it's exercised and
        // diagnostic-logged, same as DLSS, but don't composite its output
        // until that's fixed - doing so produced a black screen in testing.
        MetalNative.evaluate()
        DlssFrameTargets.markDlssOutputReady(dlssOutputReady)
        DlssFrameTargets.compositeTo(target)
    }

    private fun RenderTarget.toVulkanResources(
        backendName: String,
        deviceName: String,
        deviceHandle: Long,
        graphicsQueueHandle: Long,
        graphicsQueueFamily: Int,
        colorImage: Long,
        colorImageView: Long,
        depthImage: Long,
        depthImageView: Long,
    ) = VulkanFrameResources(
        backendName = backendName,
        deviceName = deviceName,
        deviceHandle = deviceHandle,
        graphicsQueueHandle = graphicsQueueHandle,
        graphicsQueueFamily = graphicsQueueFamily,
        width = width,
        height = height,
        colorFormat = requireNotNull(colorTexture).format.toString(),
        depthFormat = requireNotNull(depthTexture).format.toString(),
        colorImage = colorImage,
        colorImageView = colorImageView,
        depthImage = depthImage,
        depthImageView = depthImageView,
    )
}

data class VulkanFrameResources(
    val backendName: String,
    val deviceName: String,
    val deviceHandle: Long,
    val graphicsQueueHandle: Long,
    val graphicsQueueFamily: Int,
    val width: Int,
    val height: Int,
    val colorFormat: String,
    val depthFormat: String,
    val colorImage: Long,
    val colorImageView: Long,
    val depthImage: Long,
    val depthImageView: Long,
)
