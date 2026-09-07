package dev.viincnt.singularity.render

import com.mojang.blaze3d.GpuFormat
import com.mojang.blaze3d.buffers.GpuBuffer
import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.textures.GpuTexture
import com.mojang.blaze3d.vulkan.VulkanCommandEncoder
import com.mojang.blaze3d.vulkan.VulkanDevice
import com.mojang.blaze3d.vulkan.VulkanGpuTexture
import com.mojang.blaze3d.vulkan.VulkanUtils
import dev.viincnt.singularity.Singularity
import org.joml.Vector4f
import org.lwjgl.system.MemoryStack
import org.lwjgl.vulkan.VK12
import org.lwjgl.vulkan.VkClearColorValue
import org.lwjgl.vulkan.VkImageSubresourceRange

/** Records and verifies a native Vulkan command without touching the displayed frame. */
object NativeVulkanPassProbe {
    private const val SIZE = 4
    private val clearColor = Vector4f(0.25f, 0.5f, 0.75f, 1.0f)
    private var attempted = false

    fun runOnce(device: VulkanDevice) {
        if (attempted) return
        attempted = true

        val gpu = RenderSystem.getDevice()
        val texture = gpu.createTexture(
            "Singularity native-pass probe",
            GpuTexture.USAGE_COPY_SRC or GpuTexture.USAGE_COPY_DST or
                GpuTexture.USAGE_TEXTURE_BINDING or GpuTexture.USAGE_RENDER_ATTACHMENT,
            GpuFormat.RGBA8_UNORM,
            SIZE,
            SIZE,
            1,
            1,
        ) as VulkanGpuTexture
        val readback = gpu.createBuffer(
            { "Singularity native-pass readback" },
            GpuBuffer.USAGE_MAP_READ or GpuBuffer.USAGE_COPY_DST,
            (SIZE * SIZE * 4).toLong(),
        )
        val encoder = device.createCommandEncoder()
        val commandBuffer = encoder.allocateAndBeginTransientCommandBuffer()

        MemoryStack.stackPush().use { stack ->
            val clear = VulkanUtils.putArgb(VkClearColorValue.calloc(stack), clearColor)
            val range = VkImageSubresourceRange.calloc(stack)
                .aspectMask(VK12.VK_IMAGE_ASPECT_COLOR_BIT)
                .baseMipLevel(0)
                .levelCount(1)
                .baseArrayLayer(0)
                .layerCount(1)

            VK12.vkCmdClearColorImage(
                commandBuffer,
                texture.vkImage(),
                VK12.VK_IMAGE_LAYOUT_GENERAL,
                clear,
                range,
            )
            VulkanCommandEncoder.memoryBarrier(commandBuffer, stack)
        }

        VulkanUtils.crashIfFailure(
            device,
            VK12.vkEndCommandBuffer(commandBuffer),
            "Singularity failed to end native probe command buffer",
        )
        encoder.execute(commandBuffer)
        gpu.createCommandEncoder().copyTextureToBuffer(texture, readback, 0, {
            verifyReadback(texture, readback)
        }, 0)

        Singularity.LOGGER.info(
            "Queued native Vulkan pass: commandBuffer=0x{}, testImage=0x{}",
            commandBuffer.address().toString(16),
            texture.vkImage().toString(16),
        )
    }

    private fun verifyReadback(texture: VulkanGpuTexture, readback: GpuBuffer) {
        try {
            readback.map(true, false).use { mapped ->
                val bytes = mapped.data()
                val rgba = IntArray(4) { bytes.get(it).toInt() and 0xff }
                val expected = intArrayOf(64, 128, 191, 255)
                val passed = rgba.indices.all { kotlin.math.abs(rgba[it] - expected[it]) <= 1 }

                if (passed) {
                    Singularity.LOGGER.info(
                        "Native Vulkan pass verified by GPU readback: RGBA={},{},{},{}",
                        rgba[0], rgba[1], rgba[2], rgba[3],
                    )
                } else {
                    Singularity.LOGGER.error(
                        "Native Vulkan pass readback mismatch: expected RGBA=64,128,191,255; got {},{},{},{}",
                        rgba[0], rgba[1], rgba[2], rgba[3],
                    )
                }
            }
        } finally {
            readback.close()
            texture.close()
        }
    }
}
