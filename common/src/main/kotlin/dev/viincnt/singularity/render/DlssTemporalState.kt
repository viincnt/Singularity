package dev.viincnt.singularity.render

import dev.viincnt.singularity.Singularity
import net.minecraft.client.Camera
import org.joml.Matrix4f
import org.joml.Matrix4fc

/** Keeps the camera transform history that DLSS evaluation consumes. */
object DlssTemporalState {
    private var previousX: Double? = null
    private var previousY: Double? = null
    private var previousZ: Double? = null
    private var frameIndex = 0L
    private var reported = false
    private var jitterX = 0.0f
    private var jitterY = 0.0f
    private var resetRequested = true

    fun jitteredProjection(matrix: Matrix4fc, width: Int, height: Int): Matrix4f {
        if (width <= 0 || height <= 0) return Matrix4f(matrix)
        jitterX = halton(frameIndex + 1, 2) - 0.5f
        jitterY = halton(frameIndex + 1, 3) - 0.5f
        return Matrix4f(matrix).apply {
            m20(m20() + jitterX * 2.0f / width)
            m21(m21() + jitterY * 2.0f / height)
        }
    }

    fun jitterX() = jitterX
    fun jitterY() = jitterY

    fun beginFrame(camera: Camera) {
        val position = camera.position()
        val deltaX = previousX?.let { position.x - it } ?: 0.0
        val deltaY = previousY?.let { position.y - it } ?: 0.0
        val deltaZ = previousZ?.let { position.z - it } ?: 0.0
        if (previousX == null || deltaX * deltaX + deltaY * deltaY + deltaZ * deltaZ > TELEPORT_DISTANCE_SQUARED) {
            resetRequested = true
        }
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
        jitterX = 0.0f
        jitterY = 0.0f
        resetRequested = true
    }

    fun consumeResetRequest(): Boolean = resetRequested.also { resetRequested = false }

    private fun halton(index: Long, base: Int): Float {
        var value = 0.0f
        var fraction = 1.0f
        var remaining = index
        while (remaining > 0) {
            fraction /= base
            value += fraction * (remaining % base)
            remaining /= base
        }
        return value
    }

    private const val TELEPORT_DISTANCE_SQUARED = 256.0
}
