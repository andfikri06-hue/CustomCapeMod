#pragma once

#include "mc/world/actor/player/Player.h"

namespace customcape {

// Opens the main "Custom Cape" menu for a player (SimpleForm-based, in
// the spirit of your Bedrock Tool menu styling — swap in your shared
// icon/button-label helpers from that project if you have them, so this
// menu matches the rest of your mods visually).
void openCapeMenu(Player& player);

} // namespace customcape
