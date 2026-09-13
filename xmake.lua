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

target("CustomCapeMod")
    set_kind("shared")
    set_languages("c++20")
    set_strip("all")
    add_files("src/*.cpp")
    add_includedirs("src")
    add_packages("preloader", "nlohmann_json")

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
