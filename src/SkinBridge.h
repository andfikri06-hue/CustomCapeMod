#pragma once

#include "CapeData.h"

namespace customcape {

// ---------------------------------------------------------------------
// HONEST STATUS - read before expecting the cape on the 3D character.
//
// Everything else in this mod (cape folder, Bedrock Cosmos link import,
// pixel canvas, the pause-menu panel) works without touching the game's
// internals. Making the cape show up on the player's model is different:
// it means writing into Minecraft's own skin/cape data before it is
// used. LeviLaunchroid (Android client) has no typed headers for that,
// only address/pattern hooks: pl::memory::resolveSignature(...) to find
// a function in libminecraftpe.so and pl::memory::hook(...) to detour it.
//
// The pattern + the struct layout for your exact game build (26.51) still
// have to be found with a reverse-engineering pass (see scripts/ or the
// chat notes), so initSkinHook() below is a PLACEHOLDER: it logs that the
// hook is missing and skinHookInstalled() stays false. Equipping a cape
// still saves your choice and keeps it ready - it just is not drawn on the
// character until the hook exists.
// ---------------------------------------------------------------------

// Called once from enable(). Safe even while the real pattern/offsets are
// missing: it logs a warning and does nothing else.
void initSkinHook();

// True only when a real skin hook is installed and will use the cape.
bool skinHookInstalled();

// Remembers `cape` as the one to put on the local player (downscaled to the
// 64x32 vanilla cape slot) and, once the hook exists, re-sends the skin.
// Returns false for an invalid cape. Never crashes when no hook is present.
bool applyCapeToLocalPlayer(CapeData const& cape);

// Back to the player's normal (non-custom) cape.
bool clearCapeFromLocalPlayer();

} // namespace customcape
