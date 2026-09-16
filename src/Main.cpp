#include "CapeCanvasBridge.h"
#include "CapeData.h"
#include "CapeManager.h"
#include "ConfigMenu.h"
#include "SkinBridge.h"

#include <pl/Mod.hpp>

// NOTE: CapeSyncPacket / ll::network is a BDS-server-side API - the
// preloader-android package used to build this mod for LeviLaunchroid
// doesn't ship "ll/api/network/packet/PacketBase.h" at all (confirmed by
// build failure), and per CapeSyncPacket.h's own comment it was only a
// *supplementary* channel anyway. Cape visibility for other players
// already works through SkinBridge.h (embedded in the vanilla skin
// payload), so the sync-packet feature is dropped for the Android build
// rather than reimplemented. CapeSyncPacket.cpp/.h stay in the repo
// (excluded from the android build in xmake.lua) in case a future
// Windows/BDS-side build ever wants them back.

// ---------------------------------------------------------------------
// CONFIRMED against the official LeviLaunchroid mod template
// (github.com/QYCottage/levilauncher-android-mod-template,
// src/mod/MyMod.cpp) - this is real source, not docs prose:
//   - Header is <pl/Mod.hpp>, not "ll/api/mod/NativeMod.h".
//   - The class caches `ll::mod::NativeMod& mSelf` in its constructor's
//     init list via `mSelf(*ll::mod::NativeMod::current())`, exposed
//     through a getSelf() accessor - rather than calling
//     ll::mod::NativeMod::current() fresh in every method.
//   - Lifecycle is FOUR methods: load() / enable() / disable() /
//     unload() - not three. unload() runs during final mod cleanup
//     (release load()-time resources here).
//   - Logger calls are fmt-style with {} placeholders and separate
//     args (self.getLogger().error("... {}: {}", a, b)), not string
//     concatenation - matched below throughout this file.
//
// ALSO CONFIRMED (dumped directly from the CI runner's synced
// pl/ModMenu.hpp, not docs prose or guesswork) - this header's shape is
// notably not a typical fluent builder:
//   - ModuleBuilder/ButtonBuilder's terminal call is .registerModule() /
//     .registerButton() - a method on the builder itself, returning
//     bool - not .build().
//   - There's no .onClick()/.onLongPress(). Buttons only expose
//     .onEvent(callback), where callback receives
//     (buttonId, ButtonEvent, value) and ButtonEvent is an enum
//     {Click, Down, Up, StateChanged, Scroll}.
#include <pl/ModMenu.hpp>

namespace customcape {

namespace {

void wireCanvasSaveCallback() {
    setCanvasSaveCallback([](std::string const& base64Rgba, int width, int height, std::string const& name) {
        // The canvas hands us the same "cosmos_cape" shape CapeData
        // already speaks (raw RGBA8, base64), so route it straight
        // through capeFromJson-style construction.
        CapeData cape;
        cape.name   = name.empty() ? "MyCape" : name;
        cape.width  = width;
        cape.height = height;

        nlohmann::json j{{"width", width}, {"height", height}, {"pixels", base64Rgba}, {"name", cape.name}};
        if (!capeFromJson(j, cape)) {
            ll::mod::NativeMod::current()->getLogger().error("Drawn cape failed to decode - dropping it.");
            return;
        }

        if (CapeManager::getInstance().saveDrawnCape(cape)) {
            CapeManager::getInstance().setActiveCape(kCapeStorageDir / (cape.name + ".json"));
            applyCapeToLocalPlayer(cape);
        }
    });
}

} // namespace

class CustomCapeMod {
public:
    static CustomCapeMod& instance() {
        static CustomCapeMod instance;
        return instance;
    }

    ll::mod::NativeMod& getSelf() const { return mSelf; }

    bool load() {
        auto& self = getSelf();
        self.getLogger().debug("Loading...");

        std::error_code ec;
        std::filesystem::create_directories(self.getDataDir(), ec);
        if (ec) {
            self.getLogger().error("Failed to create data directory {}: {}", self.getDataDir().string(), ec.message());
            return false;
        }

        CapeManager::getInstance().ensureStorageDir();
        self.getLogger().info("Loaded {} from {}", self.getName(), self.getModDir().string());
        return true;
    }

    bool enable() {
        auto& self = getSelf();
        self.getLogger().debug("Enabling...");

        // CONFIRMED against the real pl/ModMenu.hpp (dumped straight
        // from the CI runner's synced package - see chat history for
        // the full header). Two things that differ from a typical
        // fluent builder:
        //   - The terminal call is .registerModule() / .registerButton()
        //     (a method on the builder itself, returns bool) - there is
        //     no .build().
        //   - There's no .onClick()/.onLongPress(). Buttons only have
        //     .onEvent(callback), where callback receives
        //     (buttonId, ButtonEvent, value) and ButtonEvent is an enum
        //     {Click, Down, Up, StateChanged, Scroll} - so a tap handler
        //     checks `event == pl::modmenu::ButtonEvent::Click` inside
        //     one shared callback.
        bool moduleOk = pl::modmenu::ModuleBuilder("customcape", "Custom Cape")
                             .description("Import, draw, and equip custom capes")
                             .registerModule();
        if (!moduleOk) self.getLogger().error("Failed to register the Custom Cape Mod Menu module.");

        bool capeBtnOk = pl::modmenu::ButtonBuilder("customcape_cape", "Cape")
                              .moduleId("customcape")
                              .onEvent([](std::string_view, pl::modmenu::ButtonEvent event, float) {
                                  if (event == pl::modmenu::ButtonEvent::Click) cycleActiveCape();
                              })
                              .registerButton();
        if (!capeBtnOk) self.getLogger().error("Failed to register the Cape button.");

        bool removeBtnOk = pl::modmenu::ButtonBuilder("customcape_remove", "Remove")
                                .moduleId("customcape")
                                .onEvent([](std::string_view, pl::modmenu::ButtonEvent event, float) {
                                    if (event == pl::modmenu::ButtonEvent::Click) removeActiveCape();
                                })
                                .registerButton();
        if (!removeBtnOk) self.getLogger().error("Failed to register the Remove button.");

        bool drawBtnOk = pl::modmenu::ButtonBuilder("customcape_draw", "Draw")
                              .moduleId("customcape")
                              .onEvent([](std::string_view, pl::modmenu::ButtonEvent event, float) {
                                  if (event != pl::modmenu::ButtonEvent::Click) return;
                                  if (!openCapeCanvasEditor()) {
                                      CustomCapeMod::instance().getSelf().getLogger().error(
                                          "Couldn't open the drawing canvas - see CapeCanvasBridge.cpp "
                                          "(getGameActivity() is still a placeholder there)."
                                      );
                                  }
                              })
                              .registerButton();
        if (!drawBtnOk) self.getLogger().error("Failed to register the Draw button.");

        wireCanvasSaveCallback();
        initSkinHook();

        // There's no join/session event bus on this platform, so
        // "reapply my saved cape" only happens once here, on enable -
        // not on every world join like an ll::event::ClientJoinLevelEvent
        // version would assume. Surviving an in-game respawn or skin
        // refresh depends on the hook in SkinBridge.cpp itself
        // re-reading the active cape at hook-time, not on this running
        // again.
        if (auto active = CapeManager::getInstance().getActiveCape()) {
            applyCapeToLocalPlayer(*active);
        }

        self.getLogger().info("CustomCapeMod enabled - tap Cape to cycle, Remove to clear, Draw to paint a new one.");
        return true;
    }

    bool disable() {
        getSelf().getLogger().debug("Disabling...");
        pl::modmenu::unregisterButton("customcape_draw");
        pl::modmenu::unregisterButton("customcape_remove");
        pl::modmenu::unregisterButton("customcape_cape");
        pl::modmenu::unregisterModule("customcape");
        return true;
    }

    bool unload() {
        getSelf().getLogger().debug("Unloading...");
        return true;
    }

private:
    CustomCapeMod() : mSelf(*ll::mod::NativeMod::current()) {}

    ll::mod::NativeMod& mSelf;
};

PL_REGISTER_MOD(CustomCapeMod, CustomCapeMod::instance());

} // namespace customcape
