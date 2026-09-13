#pragma once

#include "CapeData.h"

namespace customcape {

// ---------------------------------------------------------------------
// HONEST FLAG — read before wiring this into a release build.
//
// Everything else in this mod (Mod/Manifest, Form menus, the custom
// network packet, the Event listeners) is built directly against
// LeviLamina's documented `ll::` API, verified against the current docs.
//
// This file is the one exception. Making the cape show up on the
// player's actual 3D model — for *every* nearby player, including ones
// not running this mod — means writing into Minecraft's own internal
// skin/cape data before it goes out on the wire. That internal
// structure lives in LeviLamina's reverse-engineered `mc::` headers
// (not `ll::`), and the exact header path / class name can shift
// between Minecraft releases (you're targeting Bedrock 26.45).
//
// The fields below (skin id, skin/cape width+height+RGBA pixels, a
// "premium/persona" flag) are the same fields Bedrock's skin payload
// has used for years and that tools like Bedrock Cosmos rely on — so
// the *shape* of this is solid. Before shipping, confirm the exact
// symbol for your build with LeviLamina's own tools:
//   - Find Function Guide: locate the function that serializes the
//     local player's SerializedSkin before PlayerSkinPacket is sent.
//   - Hook Guide: hook that function to overwrite the cape
//     width/height/pixels with applyCapeToLocalPlayer()'s output below.
// ---------------------------------------------------------------------

// Rewrites the local player's cape (skin id kept as-is) with `cape`,
// and re-sends the skin so nearby players immediately see the change.
// `cape` should already be downscaled via downscaleCape() to whatever
// cape texture size your target build's SerializedSkin expects.
//
// Returns false if no skin-serialize hook has been wired up yet
// (safe no-op fallback — the mod still works for storage/preview/canvas
// even before this piece is hooked in).
bool applyCapeToLocalPlayer(CapeData const& cape);

// Reverts to the player's normal (non-custom) cape.
bool clearCapeFromLocalPlayer();

} // namespace customcape
