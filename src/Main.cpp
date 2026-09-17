#include "CapeCanvasBridge.h"
#include "CapeData.h"
#include "CapeManager.h"
#include "ConfigMenu.h"
#include "PixelCanvas.h"
#include "SkinBridge.h"

#include <thread>

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

// Derives a cape name from a URL's filename component, e.g.
// ".../zips/slimecape.zip" -> "slimecape", ".../zips/levicape.zip?x=1"
// -> "levicape". Used instead of the downloaded *local* file's stem,
// which is always the fixed staging filename "downloaded_cape"
// (CapeDownloadHelper.java always writes to that same name) and would
// otherwise make every downloaded cape collide under one name,
// overwriting each other in storage regardless of which cape it
// actually was.
std::string nameFromUrl(std::string const& url) {
    auto        lastSlash = url.find_last_of('/');
    std::string tail      = (lastSlash == std::string::npos) ? url : url.substr(lastSlash + 1);

    auto queryPos = tail.find('?');
    if (queryPos != std::string::npos) tail = tail.substr(0, queryPos);

    auto lastDot = tail.find_last_of('.');
    if (lastDot != std::string::npos) tail = tail.substr(0, lastDot);

    return tail.empty() ? "CosmosCape" : tail;
}

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

// Bound to the "cosmos_source" Text config field (see enable() below).
// Handles a LOCAL file path (confirmed working - reuses
// CapeManager::importImageAsCape(), which the codebase already had a
// comment anticipating "Bedrock Cosmos-style drag-and-drop capes"), and
// now also a direct http(s):// link via CapeDownloadHelper.java (see
// CapeCanvasBridge.h/.cpp) - confirmed against a real Bedrock Cosmos
// marketplace-style manifest that its actual cape asset is served as a
// plain .zip at capes.bedrock-cosmos.app/zips/<name>.zip.
//
// Runs the download on a background std::thread - downloadCapeFromUrl()
// blocks on network I/O, and this gets called from the Mod Menu's
// onConfigChanged callback, which almost certainly fires on the game's
// main/render thread. Every function called from that background
// thread here (downloadCapeFromUrl, importImageAsCape, setActiveCape,
// applyCapeToLocalPlayer) is already mutex/atomic-guarded internally,
// so this is safe.
//
// STILL A HEURISTIC, not confirmed: which PNG inside the zip is the
// actual cape texture, if there's more than one (see the NOTE 2 comment
// in CapeDownloadHelper.java). Works as-is if the zip only has one PNG.
void importCapeFromSource(std::string const& source) {
    auto& log = ll::mod::NativeMod::current()->getLogger();

    if (source.rfind("http://", 0) == 0 || source.rfind("https://", 0) == 0) {
        std::thread([source]() {
            auto& bgLog = ll::mod::NativeMod::current()->getLogger();

            auto localPath = downloadCapeFromUrl(source);
            if (!localPath) {
                bgLog.error("Couldn't download a cape from: {}", source);
                return;
            }

            auto name = nameFromUrl(source);
            if (!CapeManager::getInstance().importImageAsCape(*localPath, name)) {
                bgLog.error("Downloaded the file but couldn't import it as a cape: {}", localPath->string());
                return;
            }

            CapeManager::getInstance().setActiveCape(kCapeStorageDir / (name + ".json"));
            if (auto cape = CapeManager::getInstance().getActiveCape()) {
                applyCapeToLocalPlayer(*cape);
            }
            bgLog.info("Downloaded and equipped cape: {}", name);
        }).detach();
        return;
    }

    std::filesystem::path path(source);
    if (!std::filesystem::exists(path)) {
        log.error("File not found: {}", path.string());
        return;
    }

    auto name = path.stem().string();
    if (!CapeManager::getInstance().importImageAsCape(path, name)) {
        log.error("Couldn't import cape from: {}", path.string());
        return;
    }

    CapeManager::getInstance().setActiveCape(kCapeStorageDir / (name + ".json"));
    if (auto cape = CapeManager::getInstance().getActiveCape()) {
        applyCapeToLocalPlayer(*cape);
    }
    log.info("Imported and equipped cape: {}", name);
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
        // BUG FIX: ModuleInfo::defaultEnabled defaults to false in the
        // header (`bool defaultEnabled{};`). Without calling this, the
        // module registers successfully but starts OFF, which is almost
        // certainly why the config/menu wasn't showing up in-game even
        // though the mod clearly loaded (it already created the
        // customcape storage folder - that only happens in load()).
        bool moduleOk = pl::modmenu::ModuleBuilder("customcape", "Custom Cape")
                             .description("Import, draw, and equip custom capes")
                             .defaultEnabled(true)
                             .config(
                                 "cosmos_source",
                                 "Cape: local file path, or a Bedrock Cosmos zip/png link",
                                 pl::modmenu::ConfigType::Text
                             )
                             .onConfigChanged([](std::string_view, std::string_view key, std::string_view value) {
                                 if (key == "cosmos_source" && !value.empty()) {
                                     importCapeFromSource(std::string(value));
                                 }
                             })
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

        // Was: a "Draw" button that launched a separate WebView Activity
        // via openCapeCanvasEditor() (CapeCanvasBridge.cpp/.h). That's
        // exactly what crashed in the field (SIGSEGV inside the
        // getGameActivity()/JNI Activity-lookup placeholder - see the
        // tombstone from testing). Per your request, the canvas now
        // lives INSIDE the config menu instead of a separate screen:
        // registerPixelCanvasButtons() (PixelCanvas.h/.cpp) adds a
        // D-pad + paint/color/clear/save button set that draws directly
        // into this module's own HUD overlay via
        // pl::modmenu::submitDrawCommands - no Activity, no JNI lookup,
        // so that whole crashing code path is no longer reachable from
        // any button. CapeCanvasBridge.cpp/.h and CapeCanvasActivity.java
        // are left in the repo unused rather than deleted, in case you
        // want a richer freeform canvas again later once
        // getGameActivity() is actually wired up.
        registerPixelCanvasButtons();

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
        unregisterPixelCanvasButtons();
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
