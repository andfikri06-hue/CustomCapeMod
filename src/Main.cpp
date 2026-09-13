#include "CapeCanvasBridge.h"
#include "CapeData.h"
#include "CapeManager.h"
#include "ConfigMenu.h"
#include "SkinBridge.h"

#include "ll/api/command/CommandRegistrar.h"
#include "ll/api/event/EventBus.h"
#include "ll/api/event/player/ClientJoinLevelEvent.h"
#include "ll/api/mod/NativeMod.h"

// NOTE: CapeSyncPacket / ll::network is a BDS-server-side API — the
// preloader-android package used to build this mod for LeviLaunchroid
// doesn't ship "ll/api/network/packet/PacketBase.h" at all (confirmed by
// build failure), and per CapeSyncPacket.h's own comment it was only a
// *supplementary* channel anyway. Cape visibility for other players
// already works through SkinBridge.h (embedded in the vanilla skin
// payload), so the sync-packet feature is dropped for the Android build
// rather than reimplemented. CapeSyncPacket.cpp/.h stay in the repo
// (excluded from the android build in xmake.lua) in case a future
// Windows/BDS-side build ever wants them back.

#include "mc/server/commands/CommandOrigin.h"
#include "mc/server/commands/CommandOutput.h"
#include "mc/world/actor/player/Player.h"

namespace customcape {

namespace {

void registerCapeCommand() {
    auto& cmd = ll::command::CommandRegistrar::getInstance(/*isClientSide=*/true)
                    .getOrCreateCommand("cape", "Open the Custom Cape menu");
    cmd.alias("customcape");

    cmd.overload().execute([](CommandOrigin const& origin, CommandOutput& output) {
        // NOTE: confirm CommandOrigin's exact accessor for the invoking
        // player on your headers — getEntity() + a Player cast is the
        // long-standing convention in this mod-loader family.
        auto* entity = origin.getEntity();
        auto* player = entity ? entity->getPlayer() : nullptr;
        if (!player) {
            output.error("This command can only be run by a player.");
            return;
        }
        openCapeMenu(*player);
    });
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
            ll::mod::NativeMod::current().getLogger().error("Drawn cape failed to decode — dropping it.");
            return;
        }

        if (CapeManager::getInstance().saveDrawnCape(cape)) {
            CapeManager::getInstance().setActiveCape(
                kCapeStorageDir / (cape.name + ".json")
            );
            applyCapeToLocalPlayer(cape);
        }
    });
}

void reapplySavedCapeOnJoin() {
    ll::event::EventBus::getInstance().emplaceListener<ll::event::ClientJoinLevelEvent>(
        [](ll::event::ClientJoinLevelEvent&) {
            auto active = CapeManager::getInstance().getActiveCape();
            if (active) applyCapeToLocalPlayer(*active);
        }
    );
}

} // namespace

// ---------------------------------------------------------------------
// Bootstrap. The Mod docs confirm ll::mod::NativeMod::current() plus
// mod.onLoad()/onEnable()/onDisable() as the lifecycle hook API — what
// they *don't* spell out is the very first entry point that runs when
// LeviLamina loads this .so at all (before anything below can even
// execute). The static-init pattern here is a safe, portable way to run
// setup code once the shared library is loaded, and doesn't depend on
// guessing an exported symbol name.
//
// If your other native mods already use a different proven entry point
// (some LeviLamina mod templates export a named init function instead),
// mirror that pattern here rather than this one — whichever your build
// already knows how to call.
// ---------------------------------------------------------------------

struct Bootstrap {
    Bootstrap() {
        auto& mod = ll::mod::NativeMod::current();

        mod.onLoad([](ll::mod::Mod& self) -> bool {
            self.getLogger().info("CustomCapeMod loading...");
            CapeManager::getInstance().ensureStorageDir();
            return true;
        });

        mod.onEnable([](ll::mod::Mod& self) -> bool {
            registerCapeCommand();
            wireCanvasSaveCallback();
            reapplySavedCapeOnJoin();
            self.getLogger().info("CustomCapeMod enabled — try /cape in-game.");
            return true;
        });

        mod.onDisable([](ll::mod::Mod&) -> bool { return true; });
    }
};

Bootstrap const gBootstrap; // constructed once when the .so loads

} // namespace customcape
