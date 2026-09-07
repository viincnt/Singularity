package dev.viincnt.singularity.ngx

import com.mojang.blaze3d.vulkan.VulkanDevice
import com.mojang.blaze3d.vulkan.VulkanUtils
import dev.viincnt.singularity.Singularity
import dev.viincnt.singularity.render.DlssFrameTargets
import java.nio.file.Files
import java.nio.file.Path
import org.lwjgl.vulkan.VK12

object DlssNative {
    private var attempted = false
    private var loaded = false
    private var initialized = false
    private var featureCreated = false
    private var lastRecommendedOutput: Pair<Int, Int>? = null
    private var evaluationUnavailable = false
    private var evaluationReported = false

    fun initializeOnce(device: VulkanDevice) {
        if (attempted) return
        attempted = true

        if (System.getProperty("os.name").lowercase().contains("windows").not()) {
            Singularity.LOGGER.warn("DLSS NGX integration currently supports Windows only")
            return
        }

        val configuredDirectory = System.getProperty("singularity.nativeDir")
        val nativeDirectory = sequenceOf(
            configuredDirectory?.let(Path::of),
            Path.of("native", "build"),
            Path.of("..", "native", "build"),
        ).filterNotNull()
            .map { it.toAbsolutePath().normalize() }
            .firstOrNull { Files.isRegularFile(it.resolve("singularity_native.dll")) }
            ?: Path.of(configuredDirectory ?: "native/build").toAbsolutePath().normalize()
        val bridge = nativeDirectory.resolve("singularity_native.dll")
        val featureLibrary = nativeDirectory.resolve("nvngx_dlss.dll")
        if (!Files.isRegularFile(bridge) || !Files.isRegularFile(featureLibrary)) {
            Singularity.LOGGER.warn(
                "DLSS native files are missing in {}. Run native/build-native.bat before launching the client.",
                nativeDirectory,
            )
            return
        }

        val appData = Path.of("singularity", "ngx").toAbsolutePath().normalize()
        Files.createDirectories(appData)
        System.load(bridge.toString())
        loaded = true

        val vkDevice = device.vkDevice()
        val physicalDevice = vkDevice.physicalDevice
        val instance = physicalDevice.instance
        val diagnostic = initialize(
            instance.address(),
            physicalDevice.address(),
            vkDevice.address(),
            appData.toString(),
            nativeDirectory.toString(),
        )
        initialized = diagnostic.startsWith("initialized=true")
        Singularity.LOGGER.info("NVIDIA DLSS NGX capability probe: {}", diagnostic)
    }

    fun logOptimalSettingsFor(outputWidth: Int, outputHeight: Int) {
        if (!initialized || lastRecommendedOutput == outputWidth to outputHeight) return
        lastRecommendedOutput = outputWidth to outputHeight

        DlssQualityMode.entries.forEach { quality ->
            val settings = DlssOptimalSettings.parse(
                quality = quality,
                source = getOptimalSettings(outputWidth, outputHeight, quality.ngxValue),
            )
            Singularity.LOGGER.info("DLSS {} recommendation for {}x{}: {}", quality, outputWidth, outputHeight, settings)
        }
    }

    /**
     * Allocates the DLSS feature with NVIDIA's recommended internal resolution.
     * Evaluation remains disabled until the renderer supplies a low-resolution
     * color image and per-pixel motion vectors.
     */
    fun createFeatureOnce(device: VulkanDevice, outputWidth: Int, outputHeight: Int) {
        if (!initialized || featureCreated) return

        val quality = DlssQualityMode.QUALITY
        val settings = DlssOptimalSettings.parse(
            quality,
            getOptimalSettings(outputWidth, outputHeight, quality.ngxValue),
        )
        val inputWidth = settings.optimalWidth ?: return
        val inputHeight = settings.optimalHeight ?: return
        if (settings.result != "0x1") {
            Singularity.LOGGER.warn("DLSS feature creation skipped: {}", settings)
            return
        }

        val encoder = device.createCommandEncoder()
        val commandBuffer = encoder.allocateAndBeginTransientCommandBuffer()
        val diagnostic = createFeature(
            commandBuffer.address(),
            inputWidth,
            inputHeight,
            outputWidth,
            outputHeight,
            quality.ngxValue,
        )
        VulkanUtils.crashIfFailure(
            device,
            VK12.vkEndCommandBuffer(commandBuffer),
            "Singularity failed to end the DLSS feature creation command buffer",
        )
        encoder.execute(commandBuffer)
        featureCreated = diagnostic.contains("result=0x1")
        Singularity.LOGGER.info("NVIDIA DLSS feature creation: {}", diagnostic)
    }

    fun qualityRecommendation(outputWidth: Int, outputHeight: Int): DlssOptimalSettings? {
        if (!initialized) return null
        val quality = DlssQualityMode.QUALITY
        return DlssOptimalSettings.parse(
            quality,
            getOptimalSettings(outputWidth, outputHeight, quality.ngxValue),
        ).takeIf { it.result == "0x1" }
    }

    fun evaluate(device: VulkanDevice): Boolean {
        if (!initialized || !featureCreated || evaluationUnavailable) return false
        val resources = DlssFrameTargets.resourcesForEvaluation() ?: return false
        val encoder = device.createCommandEncoder()
        val commandBuffer = encoder.allocateAndBeginTransientCommandBuffer()
        val diagnostic = evaluateFeature(
            commandBuffer.address(),
            resources.color.vkImage(), resources.colorView.vkImageView(),
            resources.depth.vkImage(), resources.depthView.vkImageView(),
            resources.motion.vkImage(), resources.motionView.vkImageView(),
            resources.output.vkImage(), resources.outputView.vkImageView(),
            resources.inputWidth, resources.inputHeight, resources.outputWidth, resources.outputHeight,
        )
        VulkanUtils.crashIfFailure(device, VK12.vkEndCommandBuffer(commandBuffer), "Singularity failed to end the DLSS evaluation command buffer")
        encoder.execute(commandBuffer)
        if (!diagnostic.contains("result=0x1")) {
            evaluationUnavailable = true
            Singularity.LOGGER.warn("DLSS evaluation disabled; retaining the fallback composition: {}", diagnostic)
        } else if (!evaluationReported) {
            evaluationReported = true
            Singularity.LOGGER.info("NVIDIA DLSS evaluation submitted: {}", diagnostic)
        }
        return diagnostic.contains("result=0x1")
    }

    fun shutdownIfLoaded() {
        if (loaded) {
            shutdown()
            loaded = false
            initialized = false
            featureCreated = false
            lastRecommendedOutput = null
            evaluationUnavailable = false
            evaluationReported = false
        }
    }

    @JvmStatic
    private external fun initialize(
        instanceHandle: Long,
        physicalDeviceHandle: Long,
        deviceHandle: Long,
        applicationDataPath: String,
        featureLibraryPath: String,
    ): String

    @JvmStatic
    private external fun shutdown()

    @JvmStatic
    private external fun getOptimalSettings(outputWidth: Int, outputHeight: Int, quality: Int): String

    @JvmStatic
    private external fun createFeature(
        commandBufferHandle: Long,
        inputWidth: Int,
        inputHeight: Int,
        outputWidth: Int,
        outputHeight: Int,
        quality: Int,
    ): String

    @JvmStatic
    private external fun evaluateFeature(
        commandBufferHandle: Long,
        colorImage: Long, colorView: Long,
        depthImage: Long, depthView: Long,
        motionImage: Long, motionView: Long,
        outputImage: Long, outputView: Long,
        inputWidth: Int, inputHeight: Int, outputWidth: Int, outputHeight: Int,
    ): String
}

enum class DlssQualityMode(internal val ngxValue: Int) {
    PERFORMANCE(0),
    BALANCED(1),
    QUALITY(2),
    ULTRA_PERFORMANCE(3),
}

data class DlssOptimalSettings(
    val quality: DlssQualityMode,
    val result: String,
    val optimalWidth: Int?,
    val optimalHeight: Int?,
    val minWidth: Int?,
    val minHeight: Int?,
    val maxWidth: Int?,
    val maxHeight: Int?,
    val sharpness: Float?,
) {
    companion object {
        fun parse(quality: DlssQualityMode, source: String): DlssOptimalSettings {
            val values = source.split(';').mapNotNull {
                val separator = it.indexOf('=')
                if (separator < 0) null else it.substring(0, separator) to it.substring(separator + 1)
            }.toMap()
            fun dimensions(name: String): Pair<Int?, Int?> {
                val dimensions = values[name]?.split('x', limit = 2) ?: return null to null
                return dimensions.getOrNull(0)?.toIntOrNull() to dimensions.getOrNull(1)?.toIntOrNull()
            }
            val (optimalWidth, optimalHeight) = dimensions("optimal")
            val (minWidth, minHeight) = dimensions("min")
            val (maxWidth, maxHeight) = dimensions("max")
            return DlssOptimalSettings(
                quality = quality,
                result = values["result"] ?: "unknown",
                optimalWidth = optimalWidth,
                optimalHeight = optimalHeight,
                minWidth = minWidth,
                minHeight = minHeight,
                maxWidth = maxWidth,
                maxHeight = maxHeight,
                sharpness = values["sharpness"]?.toFloatOrNull(),
            )
        }
    }
}
