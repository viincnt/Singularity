package dev.viincnt.singularity.ngx

import com.mojang.blaze3d.vulkan.VulkanDevice
import dev.viincnt.singularity.Singularity
import java.nio.file.Files
import java.nio.file.Path

object DlssNative {
    private var attempted = false
    private var loaded = false

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
        Singularity.LOGGER.info("NVIDIA DLSS NGX capability probe: {}", diagnostic)
    }

    fun shutdownIfLoaded() {
        if (loaded) {
            shutdown()
            loaded = false
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
}
