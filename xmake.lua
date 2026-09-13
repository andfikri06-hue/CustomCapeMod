-- CustomCapeMod — xmake build script
--
-- NOTE: this follows the standard LeviLamina mod convention (shared lib +
-- `levilamina` package). The android/NDK toolchain block below is a
-- starting point only — copy the exact `set_toolchains(...)`,
-- `add_cxflags(...)` and NDK path setup from one of your other working
-- LeviLamina client mods (e.g. your Bedrock Tool / levilamina-client-mods
-- project), since that's already proven to compile for LeviLaunchroid on
-- your GitHub Actions runner.

add_rules("mode.debug", "mode.release")
add_repositories("levimc-repo https://github.com/LiteLDev/xmake-repo.git")

add_requires("levilamina", { configs = { target_type = "client" } })
add_requires("nlohmann_json")

target("CustomCapeMod")
    set_kind("shared")
    set_languages("c++20")
    add_files("src/*.cpp")
    add_includedirs("src")
    add_packages("levilamina", "nlohmann_json")

    -- Android / LeviLaunchroid target
    if is_plat("android") then
        add_defines("LL_TARGET_ANDROID")
        add_syslinks("log", "android")
        -- add_ldflags("-landroid_jni") -- uncomment/adjust to match your NDK setup
    end

    after_build(function (target)
        os.cp(target:targetfile(), "$(projectdir)/bin/CustomCapeMod.so")
        os.cp("$(projectdir)/manifest.json", "$(projectdir)/bin/manifest.json")
    end)
