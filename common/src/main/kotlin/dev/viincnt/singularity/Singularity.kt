package dev.viincnt.singularity

import com.mojang.logging.LogUtils

/** Loader-agnostic shared state. Each loader module (neoforge/, fabric/) owns its own entrypoint. */
object Singularity {
    const val MOD_ID = "singularity"
    val LOGGER = LogUtils.getLogger()
}
