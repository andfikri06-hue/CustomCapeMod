#include "ConfigMenu.h"

#include "CapeCanvasBridge.h"
#include "CapeData.h"
#include "CapeManager.h"
#include "CapeSyncPacket.h"
#include "SkinBridge.h"

#include "ll/api/form/ModalForm.h"
#include "ll/api/form/SimpleForm.h"

namespace customcape {

namespace {

void broadcastCapeState(Player& player, CapeData const* cape) {
    CapeSyncPacket packet;
    packet.playerName    = player.getRealName();
    packet.hasCustomCape = cape != nullptr;
    if (cape) {
        packet.capeName    = cape->name;
        packet.width       = cape->width;
        packet.height      = cape->height;
        packet.pixelsRGBA  = cape->pixelsRGBA;
    }
    packet.sendToServer();
}

void openCapePicker(Player& player) {
    auto entries = CapeManager::getInstance().listAvailableCapes();

    ll::form::SimpleForm form(
        "Choose a cape",
        entries.empty()
            ? "No capes found yet. Drop a .json or .png into:\n" + kCapeStorageDir.string()
            : "Pick a cape saved in your capes folder:"
    );

    for (auto const& entry : entries) {
        form.appendButton(entry.displayName, [path = entry.filePath](Player& p) {
            auto cape = CapeManager::getInstance().loadCape(path);
            if (!cape) {
                ll::form::ModalForm("Cape Editor", "That file couldn't be read as a cape.", "OK", "")
                    .sendTo(p);
                return;
            }
            CapeManager::getInstance().setActiveCape(path);
            applyCapeToLocalPlayer(*cape);
            broadcastCapeState(p, &*cape);
            ll::form::ModalForm("Cape Editor", "Cape equipped: " + cape->name, "Nice!", "")
                .sendTo(p);
        });
    }

    form.appendButton("« Back", [](Player& p) { openCapeMenu(p); });
    form.sendTo(player);
}

} // namespace

void openCapeMenu(Player& player) {
    auto active = CapeManager::getInstance().getActiveCape();

    std::string status = active
        ? ("Current cape: " + active->name)
        : "No custom cape equipped";
    status += "\nNearby mod users wearing custom capes: " + std::to_string(getNearbyCapeCount());

    ll::form::SimpleForm form("Custom Cape", status);

    form.appendButton("🎨  Draw a new cape", [](Player& p) {
        if (!openCapeCanvasEditor()) {
            ll::form::ModalForm(
                "Cape Editor",
                "Couldn't open the drawing canvas on this build yet — "
                "the Android bridge needs to be wired up first "
                "(see CapeCanvasBridge.cpp). You can still add capes by "
                "dropping a .json or .png into:\n" + kCapeStorageDir.string(),
                "OK", ""
            ).sendTo(p);
        }
    });

    form.appendButton("📁  Choose from folder", [](Player& p) { openCapePicker(p); });

    if (active) {
        form.appendButton("🚫  Remove custom cape", [](Player& p) {
            CapeManager::getInstance().clearActiveCape();
            clearCapeFromLocalPlayer();
            broadcastCapeState(p, nullptr);
        });
    }

    form.appendButton("🔄  Refresh capes folder", [](Player& p) {
        CapeManager::getInstance().ensureStorageDir();
        openCapeMenu(p);
    });

    form.sendTo(player);
}

} // namespace customcape
