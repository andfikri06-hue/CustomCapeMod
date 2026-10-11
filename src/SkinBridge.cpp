#include "SkinBridge.h"

#include <atomic>
#include <mutex>

#include "Runtime.h"

namespace customcape {

namespace {

std::mutex        gPendingMutex;
CapeData          gPendingCape;
std::atomic<bool> gHasPendingCape{false};
std::atomic<bool> gHookInstalled{false};

} // namespace

bool skinHookInstalled() { return gHookInstalled.load(); }

bool applyCapeToLocalPlayer(CapeData const& cape) {
    if (!cape.isValid()) return false;

    // The vanilla cape slot is 64x32. Keep the artist's full-resolution file
    // on disk, but only ever hand the game a texture that fits that slot.
    CapeData fitted = downscaleCape(cape, 64, 32);

    {
        std::lock_guard lock(gPendingMutex);
        gPendingCape = std::move(fitted);
    }
    gHasPendingCape.store(true);

    // Once a real hook exists this is where the skin gets re-sent so the new
    // cape shows up immediately instead of at the next natural resend.
    return true;
}

bool clearCapeFromLocalPlayer() {
    gHasPendingCape.store(false);
    return true;
}

// ---------------------------------------------------------------------
// PLACEHOLDER. What is still missing, in order:
//   1. A signature for the function that serializes the local player's skin
//      right before it goes out (find it in libminecraftpe.so for 26.51;
//      pl::memory::resolveSignature("<bytes>", "libminecraftpe.so")).
//   2. The real field layout of the struct that holds the cape width /
//      height / pixels at that address (do NOT assume the BDS layout).
//   3. pl::memory::hook(target, detour, &original) with a detour that, when
//      gHasPendingCape is set, writes gPendingCape into that struct and then
//      calls the original; set gHookInstalled to true only after the hook
//      was installed AND the layout was checked.
// ---------------------------------------------------------------------
void initSkinHook() {
    rt::warn("Skin hook is not installed yet: your choice is saved, but the cape is not drawn on the character "
             "until the signature for libminecraftpe.so (26.51) is found.");
}

} // namespace customcape
