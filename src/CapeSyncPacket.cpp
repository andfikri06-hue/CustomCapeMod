#include "CapeSyncPacket.h"

#include <mutex>
#include <unordered_map>

#include "ll/api/mod/NativeMod.h"

namespace customcape {

// writeVarInt/readVarInt are confirmed by LeviLamina's Network docs
// example; writeString/writeBool/writeBytes below (and their read
// counterparts) follow that same BinaryStream's normal naming
// convention but weren't in the fetched example verbatim — worth a
// quick check against ll/api/network/packet/BinaryStream.h before
// relying on this wire format.

void CapeSyncPacket::write(ll::network::BinaryStream& stream) {
    stream.writeString(playerName);
    stream.writeBool(hasCustomCape);
    if (!hasCustomCape) return;

    stream.writeString(capeName);
    stream.writeVarInt(width);
    stream.writeVarInt(height);
    stream.writeVarInt(static_cast<int32_t>(pixelsRGBA.size()));
    stream.writeBytes(pixelsRGBA.data(), pixelsRGBA.size());
}

void CapeSyncPacket::read(ll::network::BinaryStream& stream) {
    playerName    = stream.readString();
    hasCustomCape = stream.readBool();
    if (!hasCustomCape) {
        capeName.clear();
        pixelsRGBA.clear();
        return;
    }

    capeName    = stream.readString();
    width       = stream.readVarInt();
    height      = stream.readVarInt();
    auto count  = static_cast<size_t>(stream.readVarInt());
    pixelsRGBA.resize(count);
    stream.readBytes(pixelsRGBA.data(), count);
}

namespace {
std::mutex                                    gCacheMutex;
std::unordered_map<std::string, CapeData>     gNearbyCapes; // playerName -> cape
} // namespace

void CapeSyncHandler::handle(ll::network::NetworkIdentifier const&, CapeSyncPacket& packet) {
    std::lock_guard lock(gCacheMutex);

    if (!packet.hasCustomCape) {
        gNearbyCapes.erase(packet.playerName);
        return;
    }

    CapeData cape;
    cape.name       = packet.capeName;
    cape.width      = packet.width;
    cape.height     = packet.height;
    cape.pixelsRGBA = packet.pixelsRGBA;

    if (!cape.isValid()) {
        ll::mod::NativeMod::current().getLogger().warn(
            "Ignored malformed CapeSyncPacket from {}", packet.playerName
        );
        return;
    }
    gNearbyCapes[packet.playerName] = std::move(cape);
}

// Exposed for ConfigMenu's "nearby custom capes" list.
size_t getNearbyCapeCount() {
    std::lock_guard lock(gCacheMutex);
    return gNearbyCapes.size();
}

} // namespace customcape
