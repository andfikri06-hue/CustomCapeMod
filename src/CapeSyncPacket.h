#pragma once

#include <cstdint>
#include <string>
#include <vector>

#include "ll/api/network/packet/PacketBase.h"
#include "ll/api/network/packet/PacketHandlerBase.h"

namespace customcape {

// Broadcast by a mod user whenever they equip/change/remove a custom cape.
//
// This is a *supplementary* channel: the part that actually makes the
// cape visible to everyone (mod users and vanilla players alike) is the
// player's own outgoing skin data — see SkinBridge.h. This packet exists
// so that other CustomCapeMod users specifically get the exact source
// artwork (full 640x320 resolution, not the size-limited texture baked
// into the vanilla skin payload) for a crisper preview/cape browser, and
// so the config menu can show "N players nearby are wearing custom
// capes" without everyone re-uploading their PNG to the server.
class CapeSyncPacket : public ll::network::PacketBase<CapeSyncPacket> {
public:
    std::string           playerName;
    bool                  hasCustomCape = false;
    std::string           capeName;
    int32_t               width  = 0;
    int32_t               height = 0;
    std::vector<uint8_t>  pixelsRGBA; // empty when hasCustomCape == false (cape removed)

    void write(ll::network::BinaryStream& stream) override;
    void read(ll::network::BinaryStream& stream) override;
};

class CapeSyncHandler : public ll::network::PacketHandlerBase<CapeSyncHandler, CapeSyncPacket> {
public:
    void handle(ll::network::NetworkIdentifier const& source, CapeSyncPacket& packet) override;
};

// Number of other CustomCapeMod users currently known to be wearing a
// custom cape (as reported via CapeSyncPacket since this session started).
size_t getNearbyCapeCount();

} // namespace customcape
