package dev.viincnt.singularity.render

/** Shared shape of [dev.viincnt.singularity.ngx.DlssOptimalSettings] and [dev.viincnt.singularity.metal.MetalFxOptimalSettings]. */
interface UpscalerOptimalSettings {
    val optimalWidth: Int?
    val optimalHeight: Int?
}
