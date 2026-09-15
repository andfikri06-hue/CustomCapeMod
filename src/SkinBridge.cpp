#include "SkinBridge.h"

#include <atomic>
#include <cstdint>
#include <mutex>

#include <pl/Mod.hpp>

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
// PLACEHOLDER HOOK — rewritten from the previous approach, which used
// LeviLamina's typed LL_AUTO_INSTANCE_HOOK(..., LocalPlayer, ...,
// &LocalPlayer::sendLocalSkin, ...). That macro needs a real, complete
// `LocalPlayer` class to take a member-function pointer from - and that
// class doesn't exist on this Android target at all (same root cause as
// the earlier Player.h "file not found" errors: LeviLamina's typed mc::
// bindings are BDS-only).
//
// LeviLaunchroid's own Hook API is address/pattern-based instead (per
// its docs: "function detouring with a Hook API") - which is actually
// the exact same shape as your BedrockTools ExtendedRenderDistanceModule
// work: pattern-scan libminecraftpe.so with rabin2/r2 to find the target
// function's address, then install a detour that calls through to the
// original.
//
// Everything below is a PLACEHOLDER shape, not confirmed call syntax -
// you need three things from your own reverse-engineering pass, same
// workflow as the render-distance clamp:
//   1. The signature/pattern for the function that serializes the local
//      player's skin right before it's attached to the outgoing skin
//      packet (search strings like "sendLocalSkin", "SerializedSkin",
//      or scan near where the skin-change UI calls back into native).
//   2. The real classes/functions inside these three CONFIRMED real
//      headers from the preloader-android package (their existence is
//      confirmed - github.com/QYCottage/levilauncher-android-mod-template
//      lists them as the supported SDK surface - but not their exact
//      class/method names, since that wasn't fetchable to verify):
//        #include <pl/memory/Signature.hpp>  // pattern scanning
//        #include <pl/memory/Hook.hpp>       // function detouring
//        #include <pl/memory/Patch.hpp>      // raw byte patching
//      grep these once synced locally to get the real call shape:
//        find ~ -path "*preloader*" -iname "Signature.hpp" -o -iname "Hook.hpp"
//   3. The real field offsets/layout for whatever struct holds
//      capeWidth/capeHeight/capeData at the address you find - don't
//      assume the BDS SerializedSkin layout matches the Android client
//      binary's layout byte-for-byte.
// ---------------------------------------------------------------------

namespace {

using SendLocalSkinFn = void (*)(void* thisPtr, void* skinPtr);

SendLocalSkinFn gOriginalSendLocalSkin = nullptr;

void detourSendLocalSkin(void* thisPtr, void* skinPtr) {
    if (gHasPendingCape && skinPtr) {
        std::lock_guard lock(gPendingMutex);
        // PLACEHOLDER offsets - replace with the real struct layout you
        // find for the Android binary's skin payload. Writing raw bytes
        // at guessed offsets is NOT safe to ship as-is; this is here to
        // show where the write goes once you've confirmed the layout.
        // auto* skin = reinterpret_cast<SerializedSkinLayout*>(skinPtr);
        // skin->capeWidth  = gPendingCape.width;
        // skin->capeHeight = gPendingCape.height;
        // std::memcpy(skin->capeData, gPendingCape.pixelsRGBA.data(), gPendingCape.pixelsRGBA.size());
    }
    if (gOriginalSendLocalSkin) gOriginalSendLocalSkin(thisPtr, skinPtr);
}

void installSkinHook() {
    // PLACEHOLDER - replace with your actual pattern-scan + hook-install
    // calls once steps 1 and 2 above are done. Something in the shape of:
    //
    //   auto address = pl::memory::sigScan("libminecraftpe.so", "AA BB CC ? ? DD");
    //   if (address) {
    //       gOriginalSendLocalSkin = pl::hook::install(address, &detourSendLocalSkin);
    //   } else {
    //       ll::mod::NativeMod::current().getLogger().error(
    //           "CustomCapeMod: sendLocalSkin pattern not found - cape hook disabled this run."
    //       );
    //   }
    ll::mod::NativeMod::current()->getLogger().warn(
        "CustomCapeMod: skin hook not wired yet - installSkinHook() is a placeholder "
        "pending the pattern-scan pass on libminecraftpe.so (see comments above)."
    );
}

} // namespace

void initSkinHook() { installSkinHook(); }

} // namespace customcape
