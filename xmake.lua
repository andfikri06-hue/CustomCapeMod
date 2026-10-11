-- CustomCapeMod — xmake build script (FIXED for Android/LeviLaunchroid)
--
-- ROOT CAUSE dari error kemarin:
-- `levilamina` (dari repo `levimc-repo`) adalah package Windows/BDS-oriented.
-- Target Android arm64-v8a buat package ini masih berstatus "Future" di
-- docs resmi LeviLamina (belum didukung) — makanya dependency internalnya
-- `symbolprovider` gagal compile ("windows.h file not found") begitu
-- di-cross-compile ke Android.
--
-- FIX: LeviLaunchroid native mod (client Android) pakai package terpisah,
-- `preloader-android` (bukan `levilamina`), yang expose namespace `ll::`
-- yang sama tapi di-build khusus buat Android. Ini persis pattern yang
-- dipakai `BedrockTools-1.4.8/xmake.lua` yang udah terbukti compile.

add_rules("mode.debug", "mode.release")
set_policy("package.requires_lock", true)

-- Custom package definition — xmake-repo (levimc-repo) TIDAK menyediakan
-- package "preloader" ini secara built-in, jadi kita declare manual persis
-- kayak yang dipakai BedrockTools.
package("preloader")
    set_homepage("https://github.com/LiteLDev/preloader-android")
    set_description("Preloader Android")
    add_urls("https://github.com/LiteLDev/preloader-android.git")
    add_versions("main", "main")
    add_deps("cmake")
    on_install("android", function (package)
        import("package.tools.cmake").install(package)
    end)
package_end()

add_requires("preloader")
add_requires("nlohmann_json")
-- pl/Logger.hpp (pulled in by pl/Mod.hpp) directly does
-- `#include <fmt/format.h>`, but the custom "preloader" package recipe
-- above only declares `cmake` as a dep (needed to build the package
-- itself) - it doesn't propagate fmt's include path to consumers. Ship
-- fmt as its own required package so the header is actually available.
add_requires("fmt")

target("CustomCapeMod")
    set_kind("shared")
    set_languages("c++20")
    set_strip("all")
    -- Daftar file SENGAJA eksplisit (bukan src/*.cpp):
    --   * CapeSyncPacket.cpp  -> pakai ll::network yang nggak ke-ship di package
    --                            "preloader" (Android), jadi tidak ikut build.
    --   * PixelCanvas.cpp     -> canvas lama (HUD Mod Menu), sudah diganti panel
    --                            pause menu (Java). File lama aman dibiarkan/dihapus.
    -- EmbeddedDex.h dibuat otomatis oleh CI (lihat .github/workflows/build.yml).
    add_files(
        "src/Main.cpp",
        "src/Runtime.cpp",
        "src/CapeCanvasBridge.cpp",
        "src/CapeData.cpp",
        "src/CapeManager.cpp",
        "src/ConfigMenu.cpp",
        "src/SkinBridge.cpp"
    )
    add_includedirs("src")
    add_packages("preloader", "nlohmann_json", "fmt")

    -- Android / LeviLaunchroid target
    if is_plat("android") then
        add_defines("LL_TARGET_ANDROID")
        add_syslinks("log", "android")
        -- flags tambahan dari BedrockTools — aman buat dipakai bareng,
        -- hapus yang nggak relevan kalau bikin masalah di build kamu:
        -- add_cxflags("-fPIC", "-fvisibility=hidden")
        -- add_cxxflags("-fno-rtti", "-fvisibility-inlines-hidden")
    end

    after_build(function (target)
        if not target:is_plat("android") then return end
        os.cp(target:targetfile(), "$(projectdir)/bin/CustomCapeMod.so")
        os.cp("$(projectdir)/manifest.json", "$(projectdir)/bin/manifest.json")
    end)
