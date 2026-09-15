#pragma once

namespace customcape {

// Equips the next cape found in the capes storage folder, wrapping back
// to the first one after the last. Bound to the "Cape" Mod Menu button's
// tap callback in Main.cpp.
//
// There's no ll::form (popup menu) on LeviLaunchroid - that's a
// LeviLamina/BDS-only API - so cape selection is a single-button cycle
// instead of a picker list. If pl::modmenu turns out to support a real
// multi-item list once you have the headers synced locally, this is the
// natural place to swap back to a proper picker.
void cycleActiveCape();

// Clears whatever cape is currently equipped. Bound to the "Cape"
// button's long-press callback in Main.cpp.
void removeActiveCape();

} // namespace customcape
