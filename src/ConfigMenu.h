#pragma once

#include <cstdint>
#include <string>
#include <vector>

namespace customcape {

// The actions behind the pause-menu panel (CapePauseOverlay.java). They hold
// no UI code at all: the Java side calls them through JNI and shows whatever
// they return.
//
// Everything that returns a status line returns "OK: <text>" or "ERR: <text>"
// (the panel colours it green / red). None of these throw.

// Cape names found in the capes folder, joined by '\n' (empty = no capes).
std::string listCapesText();

// Name of the equipped cape, "" when none.
std::string activeCapeText();

// Equips a cape from the capes folder by name.
std::string equipCapeByName(std::string const& name);

// Takes the equipped cape off.
std::string removeActiveCape();

// Imports a PNG (already downloaded / extracted by the Java side, or a local
// file) into the capes folder and equips it.
std::string importPngAsCape(std::string const& pngPath, std::string const& name);

// Saves RGBA pixels drawn in the panel's canvas as a new cape and equips it.
std::string saveDrawnPixels(std::vector<uint8_t> const& rgba, int width, int height, std::string const& name);

} // namespace customcape
