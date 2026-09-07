package dev.viincnt.singularity.render

import dev.viincnt.singularity.Singularity
import net.minecraft.client.Camera

/** Keeps the camera transform history that DLSS evaluation consumes. */
object DlssTemporalState {
    private var previousX: Double? = null
    private var previousY: Double? = null
    private var previousZ: Double? = null
    private var frameIndex = 0L
    private var reported = false

    fun beginFrame(camera: Camera) {
        val position = camera.position()
        val deltaX = previousX?.let { position.x - it } ?: 0.0
        val deltaY = previousY?.let { position.y - it } ?: 0.0
        val deltaZ = previousZ?.let { position.z - it } ?: 0.0
        previousX = position.x
        previousY = position.y
        previousZ = position.z
        frameIndex++

        if (!reported) {
            reported = true
            Singularity.LOGGER.info(
                "DLSS temporal state active: frame={}, cameraDelta=({},{},{})",
                frameIndex, deltaX, deltaY, deltaZ,
            )
        }
    }

    fun reset() {
        previousX = null
        previousY = null
        previousZ = null
        frameIndex = 0
        reported = false
    }
}
