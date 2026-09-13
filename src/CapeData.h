#pragma once

#include <cstdint>
#include <filesystem>
#include <string>
#include <vector>

#include <nlohmann/json.hpp>

namespace customcape {

// Canvas / cape resolution ceiling agreed for this mod.
// The *editor* canvas may use up to this size; what actually gets applied
// as the live in-game cape texture is downsampled to whatever the current
// vanilla/Cosmos-compatible cape texture size is (see SkinBridge.h) — the
// full-resolution artwork is preserved in the .json so mod users always
// see the artist's original quality in the preview and cape browser.
constexpr int kCapeCanvasMaxWidth  = 640;
constexpr int kCapeCanvasMaxHeight = 320;

// One cape: raw RGBA8 pixels plus the metadata that travels with it.
struct CapeData {
    std::string          name;
    int                   width  = 0;
    int                   height = 0;
    std::vector<uint8_t>  pixelsRGBA; // width * height * 4 bytes

    bool isValid() const {
        return width > 0 && height > 0
            && pixelsRGBA.size() == static_cast<size_t>(width) * height * 4;
    }
};

// Bedrock-Cosmos-style cape JSON:
// {
//   "format": "cosmos_cape",
//   "format_version": 1,
//   "name": "MyCape",
//   "width": 64,
//   "height": 32,
//   "pixels": "<base64 RGBA8 bytes>"
// }
nlohmann::json capeToJson(CapeData const& cape);
bool           capeFromJson(nlohmann::json const& j, CapeData& out);

// Load/save a .json cape file on disk.
bool loadCapeJson(std::filesystem::path const& path, CapeData& out);
bool saveCapeJson(std::filesystem::path const& path, CapeData const& cape);

// Convert a dragged-and-dropped PNG image into a CapeData (and back), so
// players can supply either a .json or a plain .png in the capes folder.
bool loadCapeFromPng(std::filesystem::path const& path, CapeData& out, std::string const& nameHint);
bool saveCapeToPng(std::filesystem::path const& path, CapeData const& cape);

// Downscale (nearest-neighbour, cape art reads better crisp than blurred)
// a CapeData to fit within maxW x maxH — used when handing the cape off
// to SkinBridge for the actual outgoing skin packet.
CapeData downscaleCape(CapeData const& source, int maxW, int maxH);

} // namespace customcape
