#include <jni.h>
#include <vulkan/vulkan.h>

#include <filesystem>
#include <iomanip>
#include <sstream>
#include <string>

#include "nvsdk_ngx_vk.h"

namespace {
VkDevice g_device = VK_NULL_HANDLE;
bool g_initialized = false;

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
        NVSDK_NGX_VULKAN_Shutdown1(g_device);
        g_device = VK_NULL_HANDLE;
        g_initialized = false;
    }
}
