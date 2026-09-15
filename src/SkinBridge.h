#pragma once

#include "CapeData.h"

namespace customcape {

// ---------------------------------------------------------------------
// HONEST FLAG — read before wiring this into a release build.
//
// This mod targets LeviLaunchroid (Android client), not LeviLamina
// (BDS/server) - the two have different native APIs despite sharing
// some naming. Mod lifecycle, Mod Menu UI, and cape storage/canvas are
// now built against LeviLaunchroid's confirmed Preloader API (see
// Main.cpp for the specifics and doc links).
//
// This file is the one piece that can't be built against any typed,
// documented API at all on this platform. Making the cape show up on
// the player's actual 3D model means writing into Minecraft's own
// internal skin/cape data before it goes out on the wire. On LeviLamina
// (BDS) that internal structure is exposed via reverse-engineered
// `mc::` headers - but those don't exist for the Android client binary
// (libminecraftpe.so) at all. The only path here is the same
// pattern-scan + address hook workflow you already used for
// BedrockTools' ExtendedRenderDistanceModule (strings/rabin2/r2 to find
// the function, then a raw hook - see the placeholder in SkinBridge.cpp).
//
// The fields being written (skin id, skin/cape width+height+RGBA
// pixels, a "premium/persona" flag) are the same fields Bedrock's skin
// payload has used for years and that tools like Bedrock Cosmos rely
// on - so the *shape* of the data is solid. What's NOT confirmed is the
// address/pattern and the exact in-memory struct layout for your
// specific Android build (26.45) - that part needs your own
// reverse-engineering pass before this does anything at runtime.
// ---------------------------------------------------------------------

// Installs the (currently placeholder) skin-serialize hook. Call once
// from Main.cpp's enable(). Safe to call even before the real
// pattern/offsets are filled in - it just logs a warning and no-ops.
void initSkinHook();

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
