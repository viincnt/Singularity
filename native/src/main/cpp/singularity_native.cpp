#include <jni.h>
#include <vulkan/vulkan.h>

#include <filesystem>
#include <iomanip>
#include <sstream>
#include <string>

#include "nvsdk_ngx_vk.h"
#include "nvsdk_ngx_helpers.h"
#include "nvsdk_ngx_helpers_vk.h"

namespace {
VkDevice g_device = VK_NULL_HANDLE;
bool g_initialized = false;
NVSDK_NGX_Handle* g_dlssFeature = nullptr;

std::wstring toWide(JNIEnv* env, jstring value) {
    const jchar* chars = env->GetStringChars(value, nullptr);
    const jsize length = env->GetStringLength(value);
    std::wstring result(reinterpret_cast<const wchar_t*>(chars), static_cast<size_t>(length));
    env->ReleaseStringChars(value, chars);
    return result;
}

std::string resultHex(NVSDK_NGX_Result result) {
    std::ostringstream stream;
    stream << "0x" << std::hex << std::uppercase << static_cast<unsigned int>(result);
    return stream.str();
}

std::string optimalSettingsText(unsigned int outputWidth, unsigned int outputHeight, int quality) {
    if (!g_initialized) {
        return "initialized=false;result=0xBAD00007";
    }

    NVSDK_NGX_Parameter* parameters = nullptr;
    const NVSDK_NGX_Result capabilityResult = NVSDK_NGX_VULKAN_GetCapabilityParameters(&parameters);
    if (NVSDK_NGX_FAILED(capabilityResult) || parameters == nullptr) {
        return "initialized=true;result=" + resultHex(capabilityResult);
    }

    unsigned int optimalWidth = 0;
    unsigned int optimalHeight = 0;
    unsigned int maxWidth = 0;
    unsigned int maxHeight = 0;
    unsigned int minWidth = 0;
    unsigned int minHeight = 0;
    float sharpness = 0.0f;
    const NVSDK_NGX_Result result = NGX_DLSS_GET_OPTIMAL_SETTINGS(
        parameters,
        outputWidth,
        outputHeight,
        static_cast<NVSDK_NGX_PerfQuality_Value>(quality),
        &optimalWidth,
        &optimalHeight,
        &maxWidth,
        &maxHeight,
        &minWidth,
        &minHeight,
        &sharpness
    );
    NVSDK_NGX_VULKAN_DestroyParameters(parameters);

    std::ostringstream text;
    text << "initialized=true"
         << ";result=" << resultHex(result)
         << ";optimal=" << optimalWidth << 'x' << optimalHeight
         << ";min=" << minWidth << 'x' << minHeight
         << ";max=" << maxWidth << 'x' << maxHeight
         << ";sharpness=" << sharpness;
    return text.str();
}
}

extern "C" JNIEXPORT jstring JNICALL
Java_dev_viincnt_singularity_ngx_DlssNative_initialize(
    JNIEnv* env,
    jclass,
    jlong instanceHandle,
    jlong physicalDeviceHandle,
    jlong deviceHandle,
    jstring applicationDataPath,
    jstring featureLibraryPath
) {
    if (g_initialized) {
        return env->NewStringUTF("initialized=true;available=unknown;detail=already initialized");
    }

    const std::wstring appDataPath = toWide(env, applicationDataPath);
    const std::wstring libraryPath = toWide(env, featureLibraryPath);
    const wchar_t* searchPaths[] = {libraryPath.c_str()};
    NVSDK_NGX_FeatureCommonInfo featureInfo{};
    featureInfo.PathListInfo.Path = searchPaths;
    featureInfo.PathListInfo.Length = 1;

    const auto instance = reinterpret_cast<VkInstance>(instanceHandle);
    const auto physicalDevice = reinterpret_cast<VkPhysicalDevice>(physicalDeviceHandle);
    const auto device = reinterpret_cast<VkDevice>(deviceHandle);
    NVSDK_NGX_FeatureDiscoveryInfo discovery{};
    discovery.SDKVersion = NVSDK_NGX_Version_API;
    discovery.FeatureID = NVSDK_NGX_Feature_SuperSampling;
    discovery.Identifier.IdentifierType = NVSDK_NGX_Application_Identifier_Type_Project_Id;
    discovery.Identifier.v.ProjectDesc.ProjectId = "dc9a6972-5df0-4fb7-86a0-f8b79ea75ca8";
    discovery.Identifier.v.ProjectDesc.EngineType = NVSDK_NGX_ENGINE_TYPE_CUSTOM;
    discovery.Identifier.v.ProjectDesc.EngineVersion = "Minecraft 26.2 / Singularity 0.1.0";
    discovery.ApplicationDataPath = appDataPath.c_str();
    discovery.FeatureInfo = &featureInfo;

    NVSDK_NGX_FeatureRequirement requirement{};
    const NVSDK_NGX_Result requirementResult = NVSDK_NGX_VULKAN_GetFeatureRequirements(
        instance, physicalDevice, &discovery, &requirement
    );
    uint32_t instanceExtensionCount = 0;
    uint32_t deviceExtensionCount = 0;
    VkExtensionProperties* instanceExtensions = nullptr;
    VkExtensionProperties* deviceExtensions = nullptr;
    const NVSDK_NGX_Result instanceExtensionsResult =
        NVSDK_NGX_VULKAN_GetFeatureInstanceExtensionRequirements(
            &discovery, &instanceExtensionCount, &instanceExtensions
        );
    const NVSDK_NGX_Result deviceExtensionsResult =
        NVSDK_NGX_VULKAN_GetFeatureDeviceExtensionRequirements(
            instance, physicalDevice, &discovery, &deviceExtensionCount, &deviceExtensions
        );
    const NVSDK_NGX_Result initResult = NVSDK_NGX_VULKAN_Init_with_ProjectID(
        "dc9a6972-5df0-4fb7-86a0-f8b79ea75ca8",
        NVSDK_NGX_ENGINE_TYPE_CUSTOM,
        "Minecraft 26.2 / Singularity 0.1.0",
        appDataPath.c_str(),
        instance,
        physicalDevice,
        device,
        nullptr,
        nullptr,
        &featureInfo
    );

    if (NVSDK_NGX_FAILED(initResult)) {
        const std::string text = "initialized=false;initResult=" + resultHex(initResult);
        return env->NewStringUTF(text.c_str());
    }

    g_device = device;
    g_initialized = true;
    NVSDK_NGX_Parameter* parameters = nullptr;
    const NVSDK_NGX_Result capabilityResult = NVSDK_NGX_VULKAN_GetCapabilityParameters(&parameters);
    int available = 0;
    int needsUpdatedDriver = 0;
    NVSDK_NGX_Result availableResult = NVSDK_NGX_Result_FAIL_NotInitialized;
    NVSDK_NGX_Result driverResult = NVSDK_NGX_Result_FAIL_NotInitialized;
    if (NVSDK_NGX_SUCCEED(capabilityResult) && parameters != nullptr) {
        availableResult = parameters->Get(NVSDK_NGX_Parameter_SuperSampling_Available, &available);
        driverResult = parameters->Get(NVSDK_NGX_Parameter_SuperSampling_NeedsUpdatedDriver, &needsUpdatedDriver);
        NVSDK_NGX_VULKAN_DestroyParameters(parameters);
    }

    std::ostringstream text;
    text << "initialized=true"
         << ";initResult=" << resultHex(initResult)
         << ";capabilityResult=" << resultHex(capabilityResult)
         << ";availableResult=" << resultHex(availableResult)
         << ";available=" << available
         << ";driverResult=" << resultHex(driverResult)
         << ";needsUpdatedDriver=" << needsUpdatedDriver
         << ";requirementResult=" << resultHex(requirementResult)
         << ";supportFlags=" << static_cast<unsigned int>(requirement.FeatureSupported)
         << ";instanceExtensionsResult=" << resultHex(instanceExtensionsResult)
         << ";instanceExtensions=";
    for (uint32_t i = 0; i < instanceExtensionCount; ++i) {
        if (i != 0) text << ',';
        text << instanceExtensions[i].extensionName;
    }
    text << ";deviceExtensionsResult=" << resultHex(deviceExtensionsResult)
         << ";deviceExtensions=";
    for (uint32_t i = 0; i < deviceExtensionCount; ++i) {
        if (i != 0) text << ',';
        text << deviceExtensions[i].extensionName;
    }
    return env->NewStringUTF(text.str().c_str());
}

extern "C" JNIEXPORT void JNICALL
Java_dev_viincnt_singularity_ngx_DlssNative_shutdown(JNIEnv*, jclass) {
    if (g_initialized) {
        if (g_dlssFeature != nullptr) {
            NVSDK_NGX_VULKAN_ReleaseFeature(g_dlssFeature);
            g_dlssFeature = nullptr;
        }
        NVSDK_NGX_VULKAN_Shutdown1(g_device);
        g_device = VK_NULL_HANDLE;
        g_initialized = false;
    }
}

extern "C" JNIEXPORT jstring JNICALL
Java_dev_viincnt_singularity_ngx_DlssNative_createFeature(
    JNIEnv* env,
    jclass,
    jlong commandBufferHandle,
    jint inputWidth,
    jint inputHeight,
    jint outputWidth,
    jint outputHeight,
    jint quality
) {
    if (!g_initialized) {
        return env->NewStringUTF("initialized=false;result=0xBAD00007");
    }
    if (g_dlssFeature != nullptr) {
        return env->NewStringUTF("initialized=true;result=0x1;detail=already-created");
    }

    NVSDK_NGX_Parameter* parameters = nullptr;
    const NVSDK_NGX_Result parametersResult = NVSDK_NGX_VULKAN_GetCapabilityParameters(&parameters);
    if (NVSDK_NGX_FAILED(parametersResult) || parameters == nullptr) {
        const std::string text = "initialized=true;result=" + resultHex(parametersResult);
        return env->NewStringUTF(text.c_str());
    }

    NVSDK_NGX_DLSS_Create_Params createParams{};
    createParams.Feature.InWidth = static_cast<unsigned int>(inputWidth);
    createParams.Feature.InHeight = static_cast<unsigned int>(inputHeight);
    createParams.Feature.InTargetWidth = static_cast<unsigned int>(outputWidth);
    createParams.Feature.InTargetHeight = static_cast<unsigned int>(outputHeight);
    createParams.Feature.InPerfQualityValue = static_cast<NVSDK_NGX_PerfQuality_Value>(quality);
    createParams.InFeatureCreateFlags = NVSDK_NGX_DLSS_Feature_Flags_None;
    createParams.InEnableOutputSubrects = false;

    const NVSDK_NGX_Result result = NGX_VULKAN_CREATE_DLSS_EXT1(
        g_device,
        reinterpret_cast<VkCommandBuffer>(commandBufferHandle),
        1,
        1,
        &g_dlssFeature,
        parameters,
        &createParams
    );
    NVSDK_NGX_VULKAN_DestroyParameters(parameters);

    std::ostringstream text;
    text << "initialized=true"
         << ";result=" << resultHex(result)
         << ";input=" << inputWidth << 'x' << inputHeight
         << ";output=" << outputWidth << 'x' << outputHeight
         << ";quality=" << quality;
    return env->NewStringUTF(text.str().c_str());
}

extern "C" JNIEXPORT jstring JNICALL
Java_dev_viincnt_singularity_ngx_DlssNative_evaluateFeature(
    JNIEnv* env,
    jclass,
    jlong commandBufferHandle,
    jlong colorImage, jlong colorView,
    jlong depthImage, jlong depthView,
    jlong motionImage, jlong motionView,
    jlong outputImage, jlong outputView,
    jint inputWidth, jint inputHeight, jint outputWidth, jint outputHeight,
    jfloat jitterX, jfloat jitterY
) {
    if (!g_initialized || g_dlssFeature == nullptr) {
        return env->NewStringUTF("initialized=false;result=0xBAD00007");
    }

    const VkImageSubresourceRange colorRange{VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
    const VkImageSubresourceRange depthRange{VK_IMAGE_ASPECT_DEPTH_BIT, 0, 1, 0, 1};
    auto color = NVSDK_NGX_Create_ImageView_Resource_VK(
        reinterpret_cast<VkImageView>(colorView), reinterpret_cast<VkImage>(colorImage), colorRange,
        VK_FORMAT_R8G8B8A8_UNORM, inputWidth, inputHeight, false
    );
    auto depth = NVSDK_NGX_Create_ImageView_Resource_VK(
        reinterpret_cast<VkImageView>(depthView), reinterpret_cast<VkImage>(depthImage), depthRange,
        VK_FORMAT_D32_SFLOAT, inputWidth, inputHeight, false
    );
    auto motion = NVSDK_NGX_Create_ImageView_Resource_VK(
        reinterpret_cast<VkImageView>(motionView), reinterpret_cast<VkImage>(motionImage), colorRange,
        VK_FORMAT_R16G16_SFLOAT, inputWidth, inputHeight, false
    );
    auto output = NVSDK_NGX_Create_ImageView_Resource_VK(
        reinterpret_cast<VkImageView>(outputView), reinterpret_cast<VkImage>(outputImage), colorRange,
        VK_FORMAT_R16G16B16A16_SFLOAT, outputWidth, outputHeight, true
    );

    NVSDK_NGX_Parameter* parameters = nullptr;
    const NVSDK_NGX_Result parametersResult = NVSDK_NGX_VULKAN_GetCapabilityParameters(&parameters);
    if (NVSDK_NGX_FAILED(parametersResult) || parameters == nullptr) {
        return env->NewStringUTF(("initialized=true;result=" + resultHex(parametersResult)).c_str());
    }

    NVSDK_NGX_VK_DLSS_Eval_Params eval{};
    eval.Feature.pInColor = &color;
    eval.Feature.pInOutput = &output;
    eval.Feature.InSharpness = 0.35f;
    eval.pInDepth = &depth;
    eval.pInMotionVectors = &motion;
    eval.InJitterOffsetX = jitterX;
    eval.InJitterOffsetY = jitterY;
    eval.InRenderSubrectDimensions.Width = static_cast<unsigned int>(inputWidth);
    eval.InRenderSubrectDimensions.Height = static_cast<unsigned int>(inputHeight);
    eval.InReset = 0;
    eval.InMVScaleX = 1.0f;
    eval.InMVScaleY = 1.0f;
    const NVSDK_NGX_Result result = NGX_VULKAN_EVALUATE_DLSS_EXT(
        reinterpret_cast<VkCommandBuffer>(commandBufferHandle), g_dlssFeature, parameters, &eval
    );
    NVSDK_NGX_VULKAN_DestroyParameters(parameters);
    return env->NewStringUTF(("initialized=true;result=" + resultHex(result)).c_str());
}

extern "C" JNIEXPORT jstring JNICALL
Java_dev_viincnt_singularity_ngx_DlssNative_getOptimalSettings(
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
