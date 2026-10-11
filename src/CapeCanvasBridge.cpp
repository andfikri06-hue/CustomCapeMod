#include "CapeCanvasBridge.h"

#include <jni.h>

#include <cstdint>
#include <cstring>
#include <exception>
#include <mutex>
#include <string>
#include <string_view>
#include <vector>

#include "CapeManager.h"
#include "ConfigMenu.h"
#include "EmbeddedDex.h" // CI-generated: scripts/generate_embedded_dex_header.py (see build.yml)
#include "Runtime.h"

// How the Java UI gets into the game
// ----------------------------------
// The launcher cannot load extra Java for us, so the UI classes are compiled
// to a .dex by CI, hex-embedded into this .so (EmbeddedDex.h) and loaded from
// memory with Android's own dalvik.system.InMemoryDexClassLoader. Its PARENT
// loader must be the launcher's app class loader (ClassLoader.getSystemClassLoader()
// is NOT that on Android), otherwise the panel cannot see PreloaderInput /
// MinecraftActivityState, which is how it learns that the pause menu is open.
//
// The six `native` methods of CapePauseOverlay are bound with RegisterNatives
// (a dlopen()ed mod never gets JNI_OnLoad, and name-based lookup cannot find
// symbols that live in a different class loader anyway).

namespace customcape {

namespace {

constexpr char const* kOverlayClassName = "org.levimc.launcher.customcape.CapePauseOverlay";

std::mutex gMutex;                  // one JNI-facing operation at a time
jclass     gOverlayClass = nullptr; // global reference, kept for the life of the process
jmethodID  gStartMethod  = nullptr;
jmethodID  gStopMethod   = nullptr;

// ---------------------------------------------------------------------
// small JNI helpers
// ---------------------------------------------------------------------

jint attachThread(JavaVM* vm, JNIEnv** env) {
#if defined(__ANDROID__)
    return vm->AttachCurrentThread(env, nullptr);
#else
    return vm->AttachCurrentThread(reinterpret_cast<void**>(env), nullptr);
#endif
}

// Gives the current thread a JNIEnv; detaches again only if it had to attach.
class EnvScope {
public:
    explicit EnvScope(JavaVM* vm) : mVm(vm) {
        if (!vm) return;
        jint r = vm->GetEnv(reinterpret_cast<void**>(&mEnv), JNI_VERSION_1_6);
        if (r == JNI_EDETACHED) {
            if (attachThread(vm, &mEnv) == JNI_OK) {
                mAttached = true;
            } else {
                mEnv = nullptr;
            }
        } else if (r != JNI_OK) {
            mEnv = nullptr;
        }
    }
    ~EnvScope() {
        if (mAttached && mVm) mVm->DetachCurrentThread();
    }
    EnvScope(EnvScope const&)            = delete;
    EnvScope& operator=(EnvScope const&) = delete;

    JNIEnv* env() const { return mEnv; }

private:
    JavaVM* mVm       = nullptr;
    JNIEnv* mEnv      = nullptr;
    bool    mAttached = false;
};

class LocalRef {
public:
    LocalRef(JNIEnv* env, jobject obj) : mEnv(env), mObj(obj) {}
    ~LocalRef() {
        if (mObj) mEnv->DeleteLocalRef(mObj);
    }
    LocalRef(LocalRef const&)            = delete;
    LocalRef& operator=(LocalRef const&) = delete;

    jobject get() const { return mObj; }
    jclass  cls() const { return static_cast<jclass>(mObj); }
    explicit operator bool() const { return mObj != nullptr; }

private:
    JNIEnv* mEnv;
    jobject mObj;
};

bool isContinuation(char c) { return (static_cast<unsigned char>(c) & 0xC0) == 0x80; }

void appendUtf8(std::string& out, uint32_t cp) {
    if (cp < 0x80) {
        out.push_back(static_cast<char>(cp));
    } else if (cp < 0x800) {
        out.push_back(static_cast<char>(0xC0 | (cp >> 6)));
        out.push_back(static_cast<char>(0x80 | (cp & 0x3F)));
    } else if (cp < 0x10000) {
        out.push_back(static_cast<char>(0xE0 | (cp >> 12)));
        out.push_back(static_cast<char>(0x80 | ((cp >> 6) & 0x3F)));
        out.push_back(static_cast<char>(0x80 | (cp & 0x3F)));
    } else {
        out.push_back(static_cast<char>(0xF0 | (cp >> 18)));
        out.push_back(static_cast<char>(0x80 | ((cp >> 12) & 0x3F)));
        out.push_back(static_cast<char>(0x80 | ((cp >> 6) & 0x3F)));
        out.push_back(static_cast<char>(0x80 | (cp & 0x3F)));
    }
}

// Java string -> UTF-8. Done by hand because GetStringUTFChars returns
// *modified* UTF-8 (emoji / NUL come out wrong for std::filesystem).
std::string toUtf8(JNIEnv* env, jstring s) {
    std::string out;
    if (!s) return out;
    jsize n = env->GetStringLength(s);
    if (n <= 0) return out;
    jchar const* chars = env->GetStringChars(s, nullptr);
    if (!chars) return out;

    out.reserve(static_cast<size_t>(n));
    for (jsize i = 0; i < n; ++i) {
        uint32_t c = chars[i];
        if (c >= 0xD800 && c <= 0xDBFF && i + 1 < n && chars[i + 1] >= 0xDC00 && chars[i + 1] <= 0xDFFF) {
            c = 0x10000 + ((c - 0xD800) << 10) + (static_cast<uint32_t>(chars[i + 1]) - 0xDC00);
            ++i;
        } else if (c >= 0xD800 && c <= 0xDFFF) {
            c = 0xFFFD; // lone surrogate
        }
        appendUtf8(out, c);
    }
    env->ReleaseStringChars(s, chars);
    return out;
}

// UTF-8 -> Java string (via UTF-16, NewStringUTF would choke on 4-byte
// sequences). Invalid bytes become U+FFFD instead of crashing the VM.
jstring toJString(JNIEnv* env, std::string const& s) {
    std::u16string u;
    u.reserve(s.size());

    size_t i = 0;
    size_t n = s.size();
    while (i < n) {
        unsigned char b   = static_cast<unsigned char>(s[i]);
        uint32_t      cp  = 0xFFFD;
        size_t        len = 1;

        if (b < 0x80) {
            cp = b;
        } else if (b >= 0xC2 && b <= 0xDF && i + 1 < n && isContinuation(s[i + 1])) {
            cp  = ((b & 0x1Fu) << 6) | (static_cast<unsigned char>(s[i + 1]) & 0x3Fu);
            len = 2;
        } else if (b >= 0xE0 && b <= 0xEF && i + 2 < n && isContinuation(s[i + 1]) && isContinuation(s[i + 2])) {
            uint32_t v = ((b & 0x0Fu) << 12) | ((static_cast<unsigned char>(s[i + 1]) & 0x3Fu) << 6)
                       | (static_cast<unsigned char>(s[i + 2]) & 0x3Fu);
            if (v >= 0x800 && !(v >= 0xD800 && v <= 0xDFFF)) {
                cp  = v;
                len = 3;
            }
        } else if (b >= 0xF0 && b <= 0xF4 && i + 3 < n && isContinuation(s[i + 1]) && isContinuation(s[i + 2])
                   && isContinuation(s[i + 3])) {
            uint32_t v = ((b & 0x07u) << 18) | ((static_cast<unsigned char>(s[i + 1]) & 0x3Fu) << 12)
                       | ((static_cast<unsigned char>(s[i + 2]) & 0x3Fu) << 6)
                       | (static_cast<unsigned char>(s[i + 3]) & 0x3Fu);
            if (v >= 0x10000 && v <= 0x10FFFF) {
                cp  = v;
                len = 4;
            }
        }

        if (cp >= 0x10000) {
            cp -= 0x10000;
            u.push_back(static_cast<char16_t>(0xD800 + (cp >> 10)));
            u.push_back(static_cast<char16_t>(0xDC00 + (cp & 0x3FF)));
        } else {
            u.push_back(static_cast<char16_t>(cp));
        }
        i += len;
    }
    return env->NewString(reinterpret_cast<jchar const*>(u.data()), static_cast<jsize>(u.size()));
}

// Clears a pending Java exception and returns its toString() for the log.
std::string describeAndClear(JNIEnv* env) {
    if (!env->ExceptionCheck()) return "(no exception)";

    LocalRef ex(env, env->ExceptionOccurred());
    env->ExceptionClear();

    std::string text = "java exception";
    if (!ex) return text;

    LocalRef thr(env, env->FindClass("java/lang/Throwable"));
    if (!thr) {
        env->ExceptionClear();
        return text;
    }
    jmethodID toStr = env->GetMethodID(thr.cls(), "toString", "()Ljava/lang/String;");
    if (!toStr) {
        env->ExceptionClear();
        return text;
    }
    LocalRef str(env, env->CallObjectMethod(ex.get(), toStr));
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        return text;
    }
    if (str) text = toUtf8(env, static_cast<jstring>(str.get()));
    return text;
}

std::vector<uint8_t> hexDecode(std::string_view hex) {
    auto nibble = [](char c) -> int {
        if (c >= '0' && c <= '9') return c - '0';
        if (c >= 'a' && c <= 'f') return c - 'a' + 10;
        if (c >= 'A' && c <= 'F') return c - 'A' + 10;
        return -1;
    };
    std::vector<uint8_t> out;
    out.reserve(hex.size() / 2);
    for (size_t i = 0; i + 1 < hex.size(); i += 2) {
        int hi = nibble(hex[i]);
        int lo = nibble(hex[i + 1]);
        if (hi < 0 || lo < 0) return {}; // corrupt header: fail loudly instead of loading garbage
        out.push_back(static_cast<uint8_t>((hi << 4) | lo));
    }
    return out;
}

// ---------------------------------------------------------------------
// the six natives of CapePauseOverlay.java
// ---------------------------------------------------------------------

// Runs `body` with every exception contained, and returns its text to Java.
template <typename F>
jstring guarded(JNIEnv* env, F&& body) {
    std::string result;
    try {
        std::lock_guard<std::mutex> lock(gMutex);
        result = body();
    } catch (std::exception const& ex) {
        result = std::string("ERR: ") + ex.what();
    } catch (...) {
        result = "ERR: unknown error";
    }
    return toJString(env, result);
}

jstring JNICALL nativeListCapes(JNIEnv* env, jclass) {
    return guarded(env, [] { return listCapesText(); });
}

jstring JNICALL nativeActiveName(JNIEnv* env, jclass) {
    return guarded(env, [] { return activeCapeText(); });
}

jstring JNICALL nativeEquip(JNIEnv* env, jclass, jstring jname) {
    std::string name = toUtf8(env, jname);
    return guarded(env, [&] { return equipCapeByName(name); });
}

jstring JNICALL nativeRemoveCape(JNIEnv* env, jclass) {
    return guarded(env, [] { return removeActiveCape(); });
}

jstring JNICALL nativeImportPng(JNIEnv* env, jclass, jstring jpath, jstring jname) {
    std::string path = toUtf8(env, jpath);
    std::string name = toUtf8(env, jname);
    return guarded(env, [&] { return importPngAsCape(path, name); });
}

jstring JNICALL nativeSaveDrawn(JNIEnv* env, jclass, jbyteArray jrgba, jint width, jint height, jstring jname) {
    std::vector<uint8_t> rgba;
    if (jrgba) {
        jsize len = env->GetArrayLength(jrgba);
        if (len > 0 && len <= 64 * 1024 * 1024) {
            rgba.resize(static_cast<size_t>(len));
            env->GetByteArrayRegion(jrgba, 0, len, reinterpret_cast<jbyte*>(rgba.data()));
        }
    }
    std::string name = toUtf8(env, jname);
    return guarded(env, [&] { return saveDrawnPixels(rgba, width, height, name); });
}

JNINativeMethod makeNative(char const* name, char const* signature, void* fn) {
    JNINativeMethod m{};
    // the NDK declares these fields const char*, desktop JDKs plain char*
    m.name      = const_cast<decltype(m.name)>(name);
    m.signature = const_cast<decltype(m.signature)>(signature);
    m.fnPtr     = fn;
    return m;
}

// Binds the six `native` methods of CapePauseOverlay to the functions above.
bool bindNatives(JNIEnv* env, jclass overlayClass) {
    JNINativeMethod natives[] = {
        makeNative("nativeListCapes", "()Ljava/lang/String;", reinterpret_cast<void*>(&nativeListCapes)),
        makeNative("nativeActiveName", "()Ljava/lang/String;", reinterpret_cast<void*>(&nativeActiveName)),
        makeNative("nativeEquip", "(Ljava/lang/String;)Ljava/lang/String;", reinterpret_cast<void*>(&nativeEquip)),
        makeNative("nativeRemoveCape", "()Ljava/lang/String;", reinterpret_cast<void*>(&nativeRemoveCape)),
        makeNative("nativeImportPng", "(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;",
                   reinterpret_cast<void*>(&nativeImportPng)),
        makeNative("nativeSaveDrawn", "([BIILjava/lang/String;)Ljava/lang/String;",
                   reinterpret_cast<void*>(&nativeSaveDrawn)),
    };
    if (env->RegisterNatives(overlayClass, natives, static_cast<jint>(sizeof(natives) / sizeof(natives[0]))) != JNI_OK) {
        rt::error("RegisterNatives failed: " + describeAndClear(env));
        return false;
    }
    return true;
}

// ---------------------------------------------------------------------
// finding the right parent class loader
// ---------------------------------------------------------------------

// The launcher's own class loader, so the panel can reach launcher classes.
jobject findAppClassLoader(JNIEnv* env, std::string& how) {
    LocalRef classClass(env, env->FindClass("java/lang/Class"));
    if (!classClass) {
        env->ExceptionClear();
        return nullptr;
    }
    jmethodID getLoader = env->GetMethodID(classClass.cls(), "getClassLoader", "()Ljava/lang/ClassLoader;");
    if (!getLoader) {
        env->ExceptionClear();
        return nullptr;
    }

    // 1) We are inside a Java->native frame of the launcher here, so FindClass
    //    resolves launcher classes; whoever loaded them is the app loader.
    static char const* const kProbes[] = {
        "org/levimc/launcher/preloader/PreloaderInput",
        "org/levimc/launcher/core/minecraft/MinecraftActivityState",
    };
    for (char const* probe : kProbes) {
        jclass c = env->FindClass(probe);
        if (!c) {
            env->ExceptionClear();
            continue;
        }
        jobject loader = env->CallObjectMethod(c, getLoader);
        env->DeleteLocalRef(c);
        if (env->ExceptionCheck()) {
            env->ExceptionClear();
            continue;
        }
        if (loader) {
            how = std::string("FindClass(") + probe + ")";
            return loader;
        }
    }

    // 2) the Application object's loader: works from any thread, not only
    //    from a Java->native frame (hidden-API "unsupported" list: allowed).
    {
        LocalRef atClass(env, env->FindClass("android/app/ActivityThread"));
        if (atClass) {
            jmethodID current = env->GetStaticMethodID(atClass.cls(), "currentApplication", "()Landroid/app/Application;");
            if (current) {
                LocalRef app(env, env->CallStaticObjectMethod(atClass.cls(), current));
                if (app && !env->ExceptionCheck()) {
                    LocalRef ctxClass(env, env->FindClass("android/content/Context"));
                    jmethodID getCl = ctxClass ? env->GetMethodID(ctxClass.cls(), "getClassLoader", "()Ljava/lang/ClassLoader;")
                                               : nullptr;
                    if (getCl) {
                        jobject loader = env->CallObjectMethod(app.get(), getCl);
                        if (!env->ExceptionCheck() && loader) {
                            how = "ActivityThread.currentApplication().getClassLoader()";
                            return loader;
                        }
                    }
                }
            }
        }
        env->ExceptionClear();
    }

    // 3) context class loader of this thread
    {
        LocalRef threadClass(env, env->FindClass("java/lang/Thread"));
        if (threadClass) {
            jmethodID current = env->GetStaticMethodID(threadClass.cls(), "currentThread", "()Ljava/lang/Thread;");
            jmethodID getCtx  = env->GetMethodID(threadClass.cls(), "getContextClassLoader", "()Ljava/lang/ClassLoader;");
            if (current && getCtx) {
                LocalRef thread(env, env->CallStaticObjectMethod(threadClass.cls(), current));
                if (thread && !env->ExceptionCheck()) {
                    jobject loader = env->CallObjectMethod(thread.get(), getCtx);
                    if (!env->ExceptionCheck() && loader) {
                        how = "Thread.getContextClassLoader";
                        return loader;
                    }
                }
            }
        }
        env->ExceptionClear();
    }

    // 4) last resort: the dex still loads, but launcher classes may be invisible
    {
        LocalRef loaderClass(env, env->FindClass("java/lang/ClassLoader"));
        if (loaderClass) {
            jmethodID sys = env->GetStaticMethodID(loaderClass.cls(), "getSystemClassLoader", "()Ljava/lang/ClassLoader;");
            if (sys) {
                jobject loader = env->CallStaticObjectMethod(loaderClass.cls(), sys);
                if (!env->ExceptionCheck() && loader) {
                    how = "ClassLoader.getSystemClassLoader (launcher classes may be invisible)";
                    return loader;
                }
            }
        }
        env->ExceptionClear();
    }
    return nullptr;
}

// ---------------------------------------------------------------------
// dex loading
// ---------------------------------------------------------------------

bool loadOverlayClass(JNIEnv* env) {
    // static: NewDirectByteBuffer does NOT copy, and ART may keep reading from
    // this memory, so it has to live as long as the process does.
    static std::vector<uint8_t> dexBytes = hexDecode(std::string_view(kCapeDownloadHelperDexHex));

    if (dexBytes.size() < 112 || std::memcmp(dexBytes.data(), "dex\n", 4) != 0) {
        rt::error("embedded dex is empty or corrupt (" + std::to_string(dexBytes.size())
                  + " bytes). The CI 'dex' step did not produce a valid classes.dex.");
        return false;
    }
    rt::info("embedded dex: " + std::to_string(dexBytes.size()) + " bytes");

    std::string how;
    LocalRef    parent(env, findAppClassLoader(env, how));
    if (!parent) {
        rt::error("no parent class loader available");
        return false;
    }
    rt::info("dex parent class loader: " + how);

    LocalRef buffer(env, env->NewDirectByteBuffer(dexBytes.data(), static_cast<jlong>(dexBytes.size())));
    if (!buffer) {
        rt::error("NewDirectByteBuffer failed: " + describeAndClear(env));
        return false;
    }

    LocalRef dexLoaderClass(env, env->FindClass("dalvik/system/InMemoryDexClassLoader"));
    if (!dexLoaderClass) {
        rt::error("InMemoryDexClassLoader not found (needs Android 8+): " + describeAndClear(env));
        return false;
    }
    jmethodID ctor =
        env->GetMethodID(dexLoaderClass.cls(), "<init>", "(Ljava/nio/ByteBuffer;Ljava/lang/ClassLoader;)V");
    if (!ctor) {
        rt::error("InMemoryDexClassLoader constructor not found: " + describeAndClear(env));
        return false;
    }
    LocalRef dexLoader(env, env->NewObject(dexLoaderClass.cls(), ctor, buffer.get(), parent.get()));
    if (!dexLoader) {
        rt::error("could not create InMemoryDexClassLoader: " + describeAndClear(env));
        return false;
    }

    LocalRef loaderBase(env, env->FindClass("java/lang/ClassLoader"));
    if (!loaderBase) {
        rt::error("java.lang.ClassLoader not found: " + describeAndClear(env));
        return false;
    }
    jmethodID loadClass = env->GetMethodID(loaderBase.cls(), "loadClass", "(Ljava/lang/String;)Ljava/lang/Class;");
    if (!loadClass) {
        rt::error("ClassLoader.loadClass not found: " + describeAndClear(env));
        return false;
    }
    LocalRef className(env, env->NewStringUTF(kOverlayClassName));
    LocalRef overlay(env, env->CallObjectMethod(dexLoader.get(), loadClass, className.get()));
    if (!overlay || env->ExceptionCheck()) {
        rt::error(std::string("could not load ") + kOverlayClassName + ": " + describeAndClear(env));
        return false;
    }

    if (!bindNatives(env, overlay.cls())) return false;

    jmethodID startMethod = env->GetStaticMethodID(overlay.cls(), "start", "(Ljava/lang/String;)V");
    jmethodID stopMethod  = env->GetStaticMethodID(overlay.cls(), "stop", "()V");
    if (!startMethod || !stopMethod) {
        rt::error("start()/stop() not found in the overlay class: " + describeAndClear(env));
        return false;
    }

    jobject global = env->NewGlobalRef(overlay.get());
    if (!global) {
        rt::error("NewGlobalRef failed");
        return false;
    }
    gOverlayClass = static_cast<jclass>(global);
    gStartMethod  = startMethod;
    gStopMethod   = stopMethod;
    rt::info("overlay class loaded, natives bound");
    return true;
}

} // namespace

bool startPauseOverlay() {
    try {
        JavaVM* vm = rt::javaVm();
        if (!vm) {
            rt::error("overlay: no JavaVM (runtime not initialised)");
            return false;
        }
        EnvScope scope(vm);
        JNIEnv*  env = scope.env();
        if (!env) {
            rt::error("overlay: could not get a JNIEnv for this thread");
            return false;
        }

        std::lock_guard<std::mutex> lock(gMutex);
        if (!gOverlayClass && !loadOverlayClass(env)) return false;

        LocalRef dir(env, toJString(env, kCapeStorageDir.string()));
        env->CallStaticVoidMethod(gOverlayClass, gStartMethod, dir.get());
        if (env->ExceptionCheck()) {
            rt::error("overlay start() threw: " + describeAndClear(env));
            return false;
        }
        rt::info("pause-menu overlay started (panel appears only while the pause menu is open)");
        return true;
    } catch (std::exception const& ex) {
        rt::error(std::string("startPauseOverlay failed: ") + ex.what());
    } catch (...) {
        rt::error("startPauseOverlay failed: unknown exception");
    }
    return false;
}

void stopPauseOverlay() {
    try {
        JavaVM* vm = rt::javaVm();
        if (!vm) return;
        EnvScope scope(vm);
        JNIEnv*  env = scope.env();
        if (!env) return;

        std::lock_guard<std::mutex> lock(gMutex);
        if (!gOverlayClass || !gStopMethod) return;

        env->CallStaticVoidMethod(gOverlayClass, gStopMethod);
        if (env->ExceptionCheck()) {
            rt::warn("overlay stop() threw: " + describeAndClear(env));
        } else {
            rt::info("pause-menu overlay stopped");
        }
    } catch (...) {
        // shutting down: nothing useful left to do
    }
}

} // namespace customcape
