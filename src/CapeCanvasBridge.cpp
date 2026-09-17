#include "CapeCanvasBridge.h"

#include <jni.h>

#include <fstream>
#include <mutex>

#include "CapeData.h"
#include "CapeManager.h"
#include <pl/Mod.hpp>

namespace customcape {

namespace {

JavaVM*           gJvm = nullptr;
std::mutex        gCallbackMutex;
CanvasSaveCallback gSaveCallback;

JNIEnv* attachEnv(bool& didAttach) {
    didAttach = false;
    if (!gJvm) return nullptr;

    JNIEnv* env = nullptr;
    auto    res = gJvm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6);
    if (res == JNI_EDETACHED) {
        if (gJvm->AttachCurrentThread(&env, nullptr) != JNI_OK) return nullptr;
        didAttach = true;
    } else if (res != JNI_OK) {
        return nullptr;
    }
    return env;
}

// PLACEHOLDER — the one LeviLaunchroid-specific unknown. Point this at
// whatever your other native mods already use to reach an
// android.app.Activity/Context (you need this same thing for a Toast,
// a system file picker, etc.). Returning a jobject global ref to the
// current game Activity is all this needs to do.
jobject getGameActivity(JNIEnv* /*env*/) {
    return nullptr; // TODO: wire to your existing "get current Activity" helper
}

// Writes the HTML app out next to the mod's other files so the WebView
// can load it via a plain file:// URI — avoids depending on any
// LeviLaunchroid-specific asset-packaging convention.
std::filesystem::path ensureCanvasHtmlOnDisk() {
    auto dir  = ll::mod::NativeMod::current()->getResourceDir();
    auto path = dir / "cape_canvas.html";
    if (!std::filesystem::exists(path)) {
        // Falls back to the copy your build already places in
        // resources/ (see xmake.lua's after_build step) — this branch
        // only fires if that copy step hasn't run yet.
        auto shipped = ll::mod::NativeMod::current()->getModDir() / "assets" / "cape_canvas.html";
        std::error_code ec;
        std::filesystem::create_directories(dir, ec);
        std::filesystem::copy_file(shipped, path, std::filesystem::copy_options::overwrite_existing, ec);
    }
    return path;
}

} // namespace

void setCanvasSaveCallback(CanvasSaveCallback cb) {
    std::lock_guard lock(gCallbackMutex);
    gSaveCallback = std::move(cb);
}

bool openCapeCanvasEditor() {
    bool    didAttach = false;
    JNIEnv* env       = attachEnv(didAttach);
    if (!env) {
        ll::mod::NativeMod::current()->getLogger().error(
            "openCapeCanvasEditor: no JavaVM attached yet (JNI_OnLoad not called?)"
        );
        return false;
    }

    jobject activity = getGameActivity(env);
    if (!activity) {
        ll::mod::NativeMod::current()->getLogger().error(
            "openCapeCanvasEditor: getGameActivity() is still a placeholder — wire it up first"
        );
        if (didAttach) gJvm->DetachCurrentThread();
        return false;
    }

    auto htmlPath = ensureCanvasHtmlOnDisk();

    // Standard "start an Activity by class name with a String extra" JNI
    // dance. CapeCanvasActivity is the companion Java class shipped
    // alongside this mod (see CapeCanvasActivity.java).
    jclass activityClass = env->FindClass("org/levimc/launcher/customcape/CapeCanvasActivity");
    jclass intentClass   = env->FindClass("android/content/Intent");
    jclass contextClass  = env->FindClass("android/content/Context");

    if (activityClass && intentClass && contextClass) {
        jmethodID intentCtor = env->GetMethodID(
            intentClass, "<init>", "(Landroid/content/Context;Ljava/lang/Class;)V"
        );
        jobject intent = env->NewObject(intentClass, intentCtor, activity, activityClass);

        jmethodID putExtra = env->GetMethodID(
            intentClass, "putExtra", "(Ljava/lang/String;Ljava/lang/String;)Landroid/content/Intent;"
        );
        jstring extraKey   = env->NewStringUTF("html_path");
        jstring extraValue = env->NewStringUTF(htmlPath.string().c_str());
        env->CallObjectMethod(intent, putExtra, extraKey, extraValue);

        jmethodID startActivity = env->GetMethodID(
            contextClass, "startActivity", "(Landroid/content/Intent;)V"
        );
        env->CallVoidMethod(activity, startActivity, intent);

        env->DeleteLocalRef(extraKey);
        env->DeleteLocalRef(extraValue);
        env->DeleteLocalRef(intent);
    }

    bool ok = activityClass && intentClass && contextClass;
    if (didAttach) gJvm->DetachCurrentThread();
    return ok;
}

std::optional<std::filesystem::path> downloadCapeFromUrl(std::string const& url) {
    auto& log = ll::mod::NativeMod::current()->getLogger();

    bool    didAttach = false;
    JNIEnv* env       = attachEnv(didAttach);
    if (!env) {
        log.error("downloadCapeFromUrl: no JavaVM attached yet (JNI_OnLoad not called?)");
        return std::nullopt;
    }

    // Deliberately does NOT call getGameActivity() - CapeDownloadHelper
    // is plain networking + java.util.zip, no Activity/Context needed,
    // so this path works even before that placeholder is wired up.
    jclass helperClass = env->FindClass("org/levimc/launcher/customcape/CapeDownloadHelper");
    if (!helperClass) {
        log.error("downloadCapeFromUrl: CapeDownloadHelper.java isn't compiled into this build yet");
        if (didAttach) gJvm->DetachCurrentThread();
        return std::nullopt;
    }

    jmethodID method = env->GetStaticMethodID(
        helperClass, "downloadAndExtractCape", "(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;"
    );
    if (!method) {
        log.error("downloadCapeFromUrl: downloadAndExtractCape() method not found on CapeDownloadHelper");
        if (didAttach) gJvm->DetachCurrentThread();
        return std::nullopt;
    }

    auto destDir = ll::mod::NativeMod::current()->getDataDir() / "downloads";
    std::error_code ec;
    std::filesystem::create_directories(destDir, ec);

    jstring jUrl     = env->NewStringUTF(url.c_str());
    jstring jDestDir = env->NewStringUTF(destDir.string().c_str());

    auto jResult = static_cast<jstring>(env->CallStaticObjectMethod(helperClass, method, jUrl, jDestDir));

    std::optional<std::filesystem::path> result;
    if (jResult) {
        auto const* chars = env->GetStringUTFChars(jResult, nullptr);
        result             = std::filesystem::path(chars);
        env->ReleaseStringUTFChars(jResult, chars);
        env->DeleteLocalRef(jResult);
    } else {
        log.error("downloadCapeFromUrl: download/extract failed for {}", url);
    }

    env->DeleteLocalRef(jUrl);
    env->DeleteLocalRef(jDestDir);
    if (didAttach) gJvm->DetachCurrentThread();
    return result;
}

} // namespace customcape

// --- JNI entry points -------------------------------------------------
//
// CAVEAT worth checking early: JNI_OnLoad below is the standard way to
// grab a JavaVM*, but it only fires automatically when a .so is loaded
// via Java's System.loadLibrary(). LeviLamina mods are typically loaded
// by LeviLamina's own native mod loader (dlopen), which may bypass that
// path entirely — in which case gJvm stays null and everything in this
// file no-ops safely, but silently. If openCapeCanvasEditor() logs "no
// JavaVM attached yet", that's what's happening. The game process
// already has a JavaVM* somewhere (Bedrock's own Android client is a
// Java Activity underneath), so the fix is usually to pull that
// existing pointer from wherever LeviLaunchroid's own native layer
// caches it, rather than depending on this mod's own JNI_OnLoad firing.

extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void*) {
    customcape::gJvm = vm;
    return JNI_VERSION_1_6;
}

// Called from CapeCanvasActivity.java when the player taps "Save".
extern "C" JNIEXPORT void JNICALL
Java_org_levimc_launcher_customcape_CapeCanvasActivity_nativeOnCapeSaved(
    JNIEnv* env, jobject /*thiz*/, jstring base64Rgba, jint width, jint height, jstring name
) {
    auto const* b64Chars = env->GetStringUTFChars(base64Rgba, nullptr);
    auto const* nameChars = env->GetStringUTFChars(name, nullptr);

    std::string b64Copy(b64Chars);
    std::string nameCopy(nameChars);

    env->ReleaseStringUTFChars(base64Rgba, b64Chars);
    env->ReleaseStringUTFChars(name, nameChars);

    std::lock_guard<std::mutex> lock(customcape::gCallbackMutex);
    if (customcape::gSaveCallback) {
        customcape::gSaveCallback(b64Copy, width, height, nameCopy);
    }
}
