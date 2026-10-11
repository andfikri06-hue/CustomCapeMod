// CustomCapeMod - LeviLaunchroid native mod (Preloader API, Minecraft Bedrock 26.51)
//
// What this mod is now
// --------------------
// All UI lives in ONE floating panel (CapePauseOverlay.java, loaded from the dex
// embedded in this .so) that exists only while Minecraft's vanilla pause menu is
// open. There is deliberately NO Mod Menu module / HUD button any more:
// LeviLaunchroid hides every pl::modmenu element while the pause menu is open,
// so nothing registered there could ever appear in the pause menu.
//
// This file is only the lifecycle glue. Rules it follows:
//   * No NativeMod::current() anywhere - it is a thread_local that is null outside
//     the preloader's lifecycle calls (a Java thread calling into us would crash).
//     The ModContext handed to load()/enable()/... is copied once by rt::init().
//   * enable() never fails just because the UI could not start; the reason is
//     written to <capes folder>/customcape_log.txt instead, so one broken piece
//     can not make the whole mod disappear.

#include <exception>
#include <string>

#include <pl/Mod.hpp>

#include "CapeCanvasBridge.h"
#include "CapeManager.h"
#include "Runtime.h"
#include "SkinBridge.h"

namespace customcape {

class CustomCapeMod {
public:
    static CustomCapeMod& instance() {
        static CustomCapeMod inst;
        return inst;
    }

    bool load(ll::mod::ModContext& ctx) {
        try {
            rt::init(ctx);
            CapeManager::getInstance().ensureStorageDir();
            rt::info("load: " + ctx.name() + " " + ctx.info().version + " (folder " + kCapeStorageDir.string() + ")");
        } catch (std::exception const& ex) {
            rt::error(std::string("load: ") + ex.what());
        } catch (...) {
            rt::error("load: unknown exception");
        }
        return true;
    }

    bool enable(ll::mod::ModContext& ctx) {
        try {
            rt::init(ctx);
            CapeManager::getInstance().ensureStorageDir();

            initSkinHook();

            // Remember the saved cape (applied for real once the skin hook exists).
            if (auto active = CapeManager::getInstance().getActiveCape()) {
                applyCapeToLocalPlayer(*active);
                rt::info("saved cape restored: " + active->name);
            }

            if (startPauseOverlay()) {
                rt::info("enabled: open the vanilla pause menu to see the Custom Cape panel");
            } else {
                rt::error("enabled WITHOUT the pause-menu panel - see the lines above for the reason");
            }
        } catch (std::exception const& ex) {
            rt::error(std::string("enable: ") + ex.what());
        } catch (...) {
            rt::error("enable: unknown exception");
        }
        return true;
    }

    bool disable(ll::mod::ModContext&) {
        try {
            stopPauseOverlay();
            rt::info("disabled");
        } catch (...) {
        }
        return true;
    }

    bool unload(ll::mod::ModContext&) { return true; }

private:
    CustomCapeMod() = default;
};

} // namespace customcape

PL_REGISTER_MOD(customcape::CustomCapeMod, customcape::CustomCapeMod::instance());
