package dev.viincnt.singularity.metal

import com.mojang.blaze3d.vulkan.VulkanDevice
import dev.viincnt.singularity.Singularity
import dev.viincnt.singularity.render.DlssFrameTargets
import dev.viincnt.singularity.render.DlssTemporalState
import dev.viincnt.singularity.render.UpscalerOptimalSettings
import java.nio.file.Files
import java.nio.file.Path

/**
 * Mirrors [dev.viincnt.singularity.ngx.DlssNative], but backed by MetalFX instead of NGX.
 *
 * Minecraft has no native Metal backend; on macOS its Vulkan backend runs through
 * MoltenVK, so the VkDevice/VkImage handles [VulkanInteropProbe] already extracts
 * are, under the hood, backed by real Metal objects. The native bridge resolves
 * those objects out of the Vulkan handles via `VK_EXT_metal_objects` - the
 * standard, non-deprecated Khronos extension for this. That extension only
 * exports a device's MTLDevice and a given VkQueue's MTLCommandQueue (not
 * individual command buffers), so unlike the DLSS/NGX bridge, MetalFX
 * evaluation doesn't ride along Minecraft's own VkCommandBuffer - the native
 * side keeps its own MTLCommandBuffer, created fresh per evaluation from the
 * exported MTLCommandQueue.
 */
object MetalNative {
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

        if (System.getProperty("os.name").lowercase().contains("mac").not()) {
            Singularity.LOGGER.warn("MetalFX integration currently supports macOS only")
            return
        }

        val configuredDirectory = System.getProperty("singularity.nativeDir")
        val nativeDirectory = sequenceOf(
            configuredDirectory?.let(Path::of),
            Path.of("native", "build"),
            Path.of("..", "native", "build"),
        ).filterNotNull()
            .map { it.toAbsolutePath().normalize() }
            .firstOrNull { Files.isRegularFile(it.resolve("libsingularity_native_metal.dylib")) }
            ?: Path.of(configuredDirectory ?: "native/build").toAbsolutePath().normalize()
        val bridge = nativeDirectory.resolve("libsingularity_native_metal.dylib")
        if (!Files.isRegularFile(bridge)) {
            Singularity.LOGGER.warn(
                "MetalFX native bridge is missing in {}. Run native/build-native-macos.sh before launching the client.",
                nativeDirectory,
            )
            return
        }

        val appData = Path.of("singularity", "metalfx").toAbsolutePath().normalize()
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
            device.graphicsQueue().vkQueue().address(),
            appData.toString(),
        )
        initialized = diagnostic.startsWith("initialized=true")
        Singularity.LOGGER.info("Apple MetalFX capability probe: {}", diagnostic)
    }

    fun logOptimalSettingsFor(outputWidth: Int, outputHeight: Int) {
        if (!initialized || lastRecommendedOutput == outputWidth to outputHeight) return
        lastRecommendedOutput = outputWidth to outputHeight

        MetalFxQualityMode.entries.forEach { quality ->
            val settings = MetalFxOptimalSettings.parse(
                quality = quality,
                source = getOptimalSettings(outputWidth, outputHeight, quality.mfxValue),
            )
            Singularity.LOGGER.info("MetalFX {} recommendation for {}x{}: {}", quality, outputWidth, outputHeight, settings)
        }
    }

    fun qualityRecommendation(outputWidth: Int, outputHeight: Int): MetalFxOptimalSettings? {
        if (!initialized) return null
        val quality = MetalFxQualityMode.QUALITY
        return MetalFxOptimalSettings.parse(
            quality,
            getOptimalSettings(outputWidth, outputHeight, quality.mfxValue),
        ).takeIf { it.result == "0x1" }
    }

    /**
     * Allocates the MetalFX temporal scaler with Apple's recommended internal resolution.
     * Evaluation stays disabled until [DlssFrameTargets] has frame resources to hand it,
     * mirroring where the DLSS bridge stands today.
     */
    fun createFeatureOnce(outputWidth: Int, outputHeight: Int) {
        if (!initialized || featureCreated) return

        val quality = MetalFxQualityMode.QUALITY
        val settings = qualityRecommendation(outputWidth, outputHeight) ?: return
        val inputWidth = settings.optimalWidth ?: return
        val inputHeight = settings.optimalHeight ?: return

        val diagnostic = createFeature(inputWidth, inputHeight, outputWidth, outputHeight, quality.mfxValue)
        featureCreated = diagnostic.contains("result=0x1")
        Singularity.LOGGER.info("Apple MetalFX feature creation: {}", diagnostic)
    }

    fun evaluate(): Boolean {
        if (!initialized || !featureCreated || evaluationUnavailable) return false
        val resources = DlssFrameTargets.resourcesForEvaluation() ?: return false
        val diagnostic = evaluateFeature(
            resources.color.vkImage(),
            resources.depth.vkImage(),
            resources.motion.vkImage(),
            resources.output.vkImage(),
            resources.inputWidth, resources.inputHeight, resources.outputWidth, resources.outputHeight,
            DlssTemporalState.jitterX(), DlssTemporalState.jitterY(),
            if (DlssTemporalState.consumeResetRequest()) 1 else 0,
        )
        if (!diagnostic.contains("result=0x1")) {
            evaluationUnavailable = true
            Singularity.LOGGER.warn("MetalFX evaluation disabled; retaining the fallback composition: {}", diagnostic)
        } else if (!evaluationReported) {
            evaluationReported = true
            Singularity.LOGGER.info("Apple MetalFX evaluation submitted: {}", diagnostic)
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
        graphicsQueueHandle: Long,
        applicationDataPath: String,
    ): String

    @JvmStatic
    private external fun shutdown()

    @JvmStatic
    private external fun getOptimalSettings(outputWidth: Int, outputHeight: Int, quality: Int): String

    @JvmStatic
    private external fun createFeature(
        inputWidth: Int,
        inputHeight: Int,
        outputWidth: Int,
        outputHeight: Int,
        quality: Int,
    ): String

    @JvmStatic
    private external fun evaluateFeature(
        colorImage: Long,
        depthImage: Long,
        motionImage: Long,
        outputImage: Long,
        inputWidth: Int, inputHeight: Int, outputWidth: Int, outputHeight: Int,
        jitterX: Float, jitterY: Float, reset: Int,
    ): String
}

/** Mirrors NGX's perf/quality tiers using MetalFX's `MTLFXTemporalScalerDescriptor` scale-factor ranges. */
enum class MetalFxQualityMode(internal val mfxValue: Int) {
    PERFORMANCE(0),
    BALANCED(1),
    QUALITY(2),
    ULTRA_PERFORMANCE(3),
}

data class MetalFxOptimalSettings(
    val quality: MetalFxQualityMode,
    val result: String,
    override val optimalWidth: Int?,
    override val optimalHeight: Int?,
    val minWidth: Int?,
    val minHeight: Int?,
    val maxWidth: Int?,
    val maxHeight: Int?,
) : UpscalerOptimalSettings {
    companion object {
        fun parse(quality: MetalFxQualityMode, source: String): MetalFxOptimalSettings {
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
            return MetalFxOptimalSettings(
                quality = quality,
                result = values["result"] ?: "unknown",
                optimalWidth = optimalWidth,
                optimalHeight = optimalHeight,
                minWidth = minWidth,
                minHeight = minHeight,
                maxWidth = maxWidth,
                maxHeight = maxHeight,
            )
        }
    }
}
