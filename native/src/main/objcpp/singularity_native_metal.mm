// Metal/MetalFX bridge, parallel to native/src/main/cpp/singularity_native.cpp.
//
// Minecraft has no native Metal backend; on macOS its Vulkan backend runs
// through MoltenVK. That means the VkDevice/VkImage handles the Kotlin side
// already extracts from Mojang's Vulkan renderer are, under the hood, backed
// by real Metal objects. This bridge resolves those objects out of the
// Vulkan handles via VK_EXT_metal_objects - the standard, non-deprecated
// Khronos extension for this (MoltenVK's older VK_MVK_moltenvk vendor
// functions are deprecated and MoltenVK itself now warns they "will cause
// crashes" when mixed with handles from other Vulkan layers).
//
// VK_EXT_metal_objects only exports a device's MTLDevice and a *specific
// VkQueue's* MTLCommandQueue - there is no such thing as exporting an
// individual VkCommandBuffer as an MTLCommandBuffer. So unlike the DLSS/NGX
// bridge (which records into Minecraft's own VkCommandBuffer), this bridge
// keeps its own MTLCommandBuffer, created fresh per evaluation from the
// exported MTLCommandQueue, and commits it independently.

#define VK_USE_PLATFORM_METAL_EXT
#include <jni.h>
#include <vulkan/vulkan.h>

#import <Metal/Metal.h>
#import <MetalFX/MetalFX.h>

#include <sstream>
#include <string>

namespace {

id<MTLDevice> g_device = nil;
id<MTLCommandQueue> g_commandQueue = nil;
VkDevice g_vkDevice = VK_NULL_HANDLE;
bool g_initialized = false;
id<MTLFXTemporalScaler> g_temporalScaler = nil;

PFN_vkExportMetalObjectsEXT g_vkExportMetalObjectsEXT = nullptr;

id<MTLTexture> resolveTexture(VkImage image, VkImageAspectFlagBits plane) {
    if (image == VK_NULL_HANDLE || g_vkExportMetalObjectsEXT == nullptr) return nil;

    VkExportMetalTextureInfoEXT textureInfo{};
    textureInfo.sType = VK_STRUCTURE_TYPE_EXPORT_METAL_TEXTURE_INFO_EXT;
    textureInfo.image = image;
    textureInfo.imageView = VK_NULL_HANDLE;
    textureInfo.bufferView = VK_NULL_HANDLE;
    textureInfo.plane = plane;

    VkExportMetalObjectsInfoEXT exportInfo{};
    exportInfo.sType = VK_STRUCTURE_TYPE_EXPORT_METAL_OBJECTS_INFO_EXT;
    exportInfo.pNext = &textureInfo;

    g_vkExportMetalObjectsEXT(g_vkDevice, &exportInfo);
    return textureInfo.mtlTexture;
}

float qualityScaleFactor(int quality) {
    // Mirrors NGX's perf/quality tiers; MetalFX expresses them as a
    // continuous input/output ratio (inputContentMinScale/MaxScale) rather
    // than fixed enum values, so this picks a fixed ratio per tier instead.
    switch (quality) {
        case 0: return 3.0f; // PERFORMANCE
        case 1: return 2.3f; // BALANCED
        case 3: return 4.0f; // ULTRA_PERFORMANCE
        default: return 1.5f; // QUALITY
    }
}

std::string optimalSettingsText(unsigned int outputWidth, unsigned int outputHeight, int quality) {
    if (!g_initialized) {
        return "initialized=false;result=0xBAD00007";
    }
    if (@available(macOS 13.0, *)) {
        const float scale = qualityScaleFactor(quality);
        const unsigned int optimalWidth = static_cast<unsigned int>(outputWidth / scale);
        const unsigned int optimalHeight = static_cast<unsigned int>(outputHeight / scale);
        std::ostringstream text;
        text << "initialized=true"
             << ";result=0x1"
             << ";optimal=" << optimalWidth << 'x' << optimalHeight
             << ";min=" << optimalWidth << 'x' << optimalHeight
             << ";max=" << outputWidth << 'x' << outputHeight;
        return text.str();
    }
    return "initialized=true;result=0xBAD00008;detail=MetalFX requires macOS 13+";
}

} // namespace

extern "C" JNIEXPORT jstring JNICALL
Java_dev_viincnt_singularity_metal_MetalNative_initialize(
    JNIEnv* env,
    jclass,
    jlong /*instanceHandle*/,
    jlong /*physicalDeviceHandle*/,
    jlong deviceHandle,
    jlong graphicsQueueHandle,
    jstring /*applicationDataPath*/
) {
    if (g_initialized) {
        return env->NewStringUTF("initialized=true;available=unknown;detail=already initialized");
    }

    g_vkDevice = reinterpret_cast<VkDevice>(deviceHandle);
    const auto graphicsQueue = reinterpret_cast<VkQueue>(graphicsQueueHandle);

    g_vkExportMetalObjectsEXT = reinterpret_cast<PFN_vkExportMetalObjectsEXT>(
        vkGetDeviceProcAddr(g_vkDevice, "vkExportMetalObjectsEXT"));
    if (g_vkExportMetalObjectsEXT == nullptr) {
        return env->NewStringUTF(
            "initialized=false;result=0xBAD00009;detail=VK_EXT_metal_objects not available");
    }

    VkExportMetalCommandQueueInfoEXT queueInfo{};
    queueInfo.sType = VK_STRUCTURE_TYPE_EXPORT_METAL_COMMAND_QUEUE_INFO_EXT;
    queueInfo.queue = graphicsQueue;

    VkExportMetalDeviceInfoEXT deviceInfo{};
    deviceInfo.sType = VK_STRUCTURE_TYPE_EXPORT_METAL_DEVICE_INFO_EXT;
    deviceInfo.pNext = &queueInfo;

    VkExportMetalObjectsInfoEXT exportInfo{};
    exportInfo.sType = VK_STRUCTURE_TYPE_EXPORT_METAL_OBJECTS_INFO_EXT;
    exportInfo.pNext = &deviceInfo;

    g_vkExportMetalObjectsEXT(g_vkDevice, &exportInfo);
    g_device = deviceInfo.mtlDevice;
    g_commandQueue = queueInfo.mtlCommandQueue;

    if (g_device == nil || g_commandQueue == nil) {
        return env->NewStringUTF("initialized=false;result=0xBAD0000A;detail=export returned nil device/queue");
    }

    g_initialized = true;

    int available = 0;
    if (@available(macOS 13.0, *)) {
        available = [MTLFXTemporalScalerDescriptor supportsDevice:g_device] ? 1 : 0;
    }

    std::ostringstream text;
    text << "initialized=true"
         << ";available=" << available
         << ";device=" << [[g_device name] UTF8String];
    return env->NewStringUTF(text.str().c_str());
}

extern "C" JNIEXPORT void JNICALL
Java_dev_viincnt_singularity_metal_MetalNative_shutdown(JNIEnv*, jclass) {
    if (g_initialized) {
        g_temporalScaler = nil;
        g_commandQueue = nil;
        g_device = nil;
        g_vkDevice = VK_NULL_HANDLE;
        g_initialized = false;
    }
}

extern "C" JNIEXPORT jstring JNICALL
Java_dev_viincnt_singularity_metal_MetalNative_createFeature(
    JNIEnv* env,
    jclass,
    jint inputWidth,
    jint inputHeight,
    jint outputWidth,
    jint outputHeight,
    jint quality
) {
    if (!g_initialized) {
        return env->NewStringUTF("initialized=false;result=0xBAD00007");
    }
    if (g_temporalScaler != nil) {
        return env->NewStringUTF("initialized=true;result=0x1;detail=already-created");
    }
    if (!@available(macOS 13.0, *)) {
        return env->NewStringUTF("initialized=true;result=0xBAD00008;detail=MetalFX requires macOS 13+");
    }

    MTLFXTemporalScalerDescriptor* descriptor = [MTLFXTemporalScalerDescriptor new];
    descriptor.inputWidth = static_cast<NSUInteger>(inputWidth);
    descriptor.inputHeight = static_cast<NSUInteger>(inputHeight);
    descriptor.outputWidth = static_cast<NSUInteger>(outputWidth);
    descriptor.outputHeight = static_cast<NSUInteger>(outputHeight);
    descriptor.colorTextureFormat = MTLPixelFormatRGBA8Unorm;
    descriptor.depthTextureFormat = MTLPixelFormatDepth32Float;
    descriptor.motionTextureFormat = MTLPixelFormatRG16Float;
    descriptor.outputTextureFormat = MTLPixelFormatRGBA16Float;
    descriptor.autoExposureEnabled = false;

    g_temporalScaler = [descriptor newTemporalScalerWithDevice:g_device];

    std::ostringstream text;
    text << "initialized=true"
         << ";result=" << (g_temporalScaler != nil ? "0x1" : "0xBAD0000B")
         << ";input=" << inputWidth << 'x' << inputHeight
         << ";output=" << outputWidth << 'x' << outputHeight
         << ";quality=" << quality;
    return env->NewStringUTF(text.str().c_str());
}

extern "C" JNIEXPORT jstring JNICALL
Java_dev_viincnt_singularity_metal_MetalNative_evaluateFeature(
    JNIEnv* env,
    jclass,
    jlong colorImage,
    jlong depthImage,
    jlong motionImage,
    jlong outputImage,
    jint /*inputWidth*/, jint /*inputHeight*/, jint /*outputWidth*/, jint /*outputHeight*/,
    jfloat jitterX, jfloat jitterY, jint reset
) {
    if (!g_initialized || g_temporalScaler == nil) {
        return env->NewStringUTF("initialized=false;result=0xBAD00007");
    }
    if (!@available(macOS 13.0, *)) {
        return env->NewStringUTF("initialized=true;result=0xBAD00008;detail=MetalFX requires macOS 13+");
    }

    id<MTLTexture> color = resolveTexture(reinterpret_cast<VkImage>(colorImage), VK_IMAGE_ASPECT_COLOR_BIT);
    id<MTLTexture> depth = resolveTexture(reinterpret_cast<VkImage>(depthImage), VK_IMAGE_ASPECT_DEPTH_BIT);
    id<MTLTexture> motion = resolveTexture(reinterpret_cast<VkImage>(motionImage), VK_IMAGE_ASPECT_COLOR_BIT);
    id<MTLTexture> output = resolveTexture(reinterpret_cast<VkImage>(outputImage), VK_IMAGE_ASPECT_COLOR_BIT);
    if (color == nil || depth == nil || motion == nil || output == nil) {
        return env->NewStringUTF("initialized=true;result=0xBAD0000C;detail=VK_EXT_metal_objects texture export failed");
    }

    id<MTLCommandBuffer> commandBuffer = [g_commandQueue commandBuffer];
    if (commandBuffer == nil) {
        return env->NewStringUTF("initialized=true;result=0xBAD0000D;detail=failed to allocate MTLCommandBuffer");
    }

    g_temporalScaler.colorTexture = color;
    g_temporalScaler.depthTexture = depth;
    g_temporalScaler.motionTexture = motion;
    g_temporalScaler.outputTexture = output;
    g_temporalScaler.jitterOffsetX = jitterX;
    g_temporalScaler.jitterOffsetY = jitterY;
    g_temporalScaler.motionVectorScaleX = 1.0f;
    g_temporalScaler.motionVectorScaleY = 1.0f;
    g_temporalScaler.reset = reset != 0;

    [g_temporalScaler encodeToCommandBuffer:commandBuffer];
    [commandBuffer commit];

    return env->NewStringUTF("initialized=true;result=0x1");
}

extern "C" JNIEXPORT jstring JNICALL
Java_dev_viincnt_singularity_metal_MetalNative_getOptimalSettings(
    JNIEnv* env,
    jclass,
    jint outputWidth,
    jint outputHeight,
    jint quality
) {
    const std::string text = optimalSettingsText(
        static_cast<unsigned int>(outputWidth),
        static_cast<unsigned int>(outputHeight),
        quality
    );
    return env->NewStringUTF(text.c_str());
}
