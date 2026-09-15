#include "ConfigMenu.h"

#include "CapeData.h"
#include "CapeManager.h"
#include "SkinBridge.h"

#include <pl/Mod.hpp>

// NOTE: broadcastCapeState()/CapeSyncPacket dropped for the Android build -
// see the comment in Main.cpp. Other players already see your cape via the
// vanilla skin payload (SkinBridge.h); this only removes the "N nearby mod
// users" extra, not cape visibility itself.
//
// NOTE 2: this file used to build a popup menu with ll::form::SimpleForm /
// ModalForm - that's a LeviLamina (BDS/server) API and doesn't exist on
// LeviLaunchroid at all (confirmed against the official docs and the
// QYCottage mod template). There's no confirmed popup-form equivalent
// here, so cape selection is collapsed into a single cycle-through action
// wired to a Mod Menu HUD button (see Main.cpp) instead of a picker list.

namespace customcape {

void cycleActiveCape() {
    auto& log = ll::mod::NativeMod::current()->getLogger();

    auto entries = CapeManager::getInstance().listAvailableCapes();
    if (entries.empty()) {
        log.warn("No capes found in {} - drop a .json or .png there first.", kCapeStorageDir.string());
        return;
    }

    // Find the currently active cape's position so "cycle" advances to
    // the next one, rather than always jumping back to the first.
    auto   active    = CapeManager::getInstance().getActiveCape();
    size_t nextIndex = 0;
    if (active) {
        for (size_t i = 0; i < entries.size(); ++i) {
            if (entries[i].displayName == active->name) {
                nextIndex = (i + 1) % entries.size();
                break;
            }
        }
    }

    auto const& next = entries[nextIndex];
    auto        cape = CapeManager::getInstance().loadCape(next.filePath);
    if (!cape) {
        log.error("Cape file couldn't be read: {}", next.filePath.string());
        return;
    }

    CapeManager::getInstance().setActiveCape(next.filePath);
    applyCapeToLocalPlayer(*cape);
    log.info("Cape equipped: {}", cape->name);
}

void removeActiveCape() {
    CapeManager::getInstance().clearActiveCape();
    clearCapeFromLocalPlayer();
    ll::mod::NativeMod::current()->getLogger().info("Custom cape removed.");
}

} // namespace customcape
