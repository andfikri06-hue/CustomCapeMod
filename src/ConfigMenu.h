#pragma once

// mc/world/actor/player/Player.h doesn't exist for this Android /
// preloader-android target - that header tree is only generated for
// LeviLamina's BDS (server) build. A forward declaration is enough
// here since this file only ever passes Player around by reference,
// it never touches its members.
class Player;

namespace customcape {

// Opens the main "Custom Cape" menu for a player (SimpleForm-based, in
// the spirit of your Bedrock Tool menu styling - swap in your shared
// icon/button-label helpers from that project if you have them, so this
// menu matches the rest of your mods visually).
void openCapeMenu(Player& player);

} // namespace customcape
