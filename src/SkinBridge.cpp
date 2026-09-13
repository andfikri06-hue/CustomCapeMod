#include "SkinBridge.h"

#include <atomic>
#include <mutex>

#include "ll/api/memory/Hook.h"
#include "ll/api/mod/NativeMod.h"

// PLACEHOLDER — swap for the real header once you've located the function
// with the Find Function Guide (https://lamina.levimc.org/developer_guides/
// how_to_guides/find_function_guide/). Look for whatever builds the local
// player's SerializedSkin right before it's attached to the outgoing skin
// packet — on past Bedrock versions this has lived on LocalPlayer or on a
// small SerializedSkin builder type.
//
// NOTE: This header only exists on LeviLamina's Windows/BDS build.
// For Android (preloader-android), the hook is disabled.
#ifdef LL_TARGET_WINDOWS
#include "mc/world/actor/player/LocalPlayer.h"
#endif

namespace customcape {

namespace {
std::mutex   gPendingMutex;
CapeData     gPendingCape;
std::atomic  gHasPendingCape{false};
} // namespace

bool applyCapeToLocalPlayer(CapeData const& cape) {
    if (!cape.isValid()) return false;

    // Fit to whatever your build's SerializedSkin cape slot expects.
    // 64x32 is the long-standing vanilla default; confirm against the
    // CSPJ106 "Cape Size Error" limits for 26.45 before shipping.
    CapeData fitted = downscaleCape(cape, 64, 32);

    {
        std::lock_guard lock(gPendingMutex);
        gPendingCape = std::move(fitted);
    }
    gHasPendingCape = true;

    // TODO: trigger the same call the client normally makes to
    // re-send its skin after a player-initiated skin change (e.g. after
    // closing the vanilla "Change Skin" screen) so the hook below fires
    // immediately instead of waiting for the next natural resend.
    return true;
}

bool clearCapeFromLocalPlayer() {
    gHasPendingCape = false;
    return true;
}

// ---------------------------------------------------------------------
// PLACEHOLDER HOOK — class name, method name, and signature all need to
// match your actual 26.45 headers. This shows the *shape*: intercept the
// skin right before it's serialized, overwrite the cape fields only
// (skin id / geometry untouched), then let the original continue so the
// normal PlayerSkinPacket send path takes it from there.
//
// NOTE: This hook only compiles and runs on LeviLamina's Windows/BDS build.
// For Android (preloader-android), it's disabled.
// ---------------------------------------------------------------------
#ifdef LL_TARGET_WINDOWS
LL_AUTO_INSTANCE_HOOK(
    CustomCapeSkinHook,
    LocalPlayer,
    void,
    &LocalPlayer::sendLocalSkin, // <- placeholder method name, confirm via Find Function Guide
    ::SerializedSkin& skin
) {
    if (gHasPendingCape) {
        std::lock_guard lock(gPendingMutex);
        // Field names below match Bedrock's long-standing public skin
        // payload shape (capeWidth/capeHeight/capeData); adjust to
        // whatever your generated SerializedSkin header actually calls
        // them.
        skin.capeWidth  = gPendingCape.width;
        skin.capeHeight = gPendingCape.height;
        skin.capeData.assign(gPendingCape.pixelsRGBA.begin(), gPendingCape.pixelsRGBA.end());
    }
    origin(skin);
}
#endif

} // namespace customcape
