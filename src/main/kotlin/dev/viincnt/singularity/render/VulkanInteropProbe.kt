package dev.viincnt.singularity.render

import com.mojang.blaze3d.pipeline.RenderTarget
import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.vulkan.VulkanDevice
import com.mojang.blaze3d.vulkan.VulkanGpuTexture
import com.mojang.blaze3d.vulkan.VulkanGpuTextureView
import dev.viincnt.singularity.Singularity
import dev.viincnt.singularity.mixin.GpuDeviceAccessor
import dev.viincnt.singularity.ngx.DlssNative
import net.minecraft.client.Minecraft
import net.neoforged.neoforge.client.event.RenderLevelStageEvent

/**
 * Discovers the native resources that a DLSS implementation will consume.
 *
 * AfterLevel is the useful boundary: world rendering is complete, while the
 * main depth texture still contains scene depth and the GUI has not been drawn.
 */
object VulkanInteropProbe {
    private var lastSnapshot: VulkanFrameResources? = null
    private var reportedNonVulkanBackend = false

    fun onAfterLevel(event: RenderLevelStageEvent.AfterLevel) {
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
