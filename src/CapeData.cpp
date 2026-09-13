#include "CapeData.h"

#include <algorithm>
#include <fstream>

#define STB_IMAGE_IMPLEMENTATION
#include "stb_image.h"
#define STB_IMAGE_WRITE_IMPLEMENTATION
#include "stb_image_write.h"

namespace customcape {

namespace {

// --- minimal base64 (no external dependency needed for this) ---------

const char kB64Chars[] =
    "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";

std::string base64Encode(std::vector<uint8_t> const& data) {
    std::string out;
    out.reserve(((data.size() + 2) / 3) * 4);
    size_t i = 0;
    while (i + 2 < data.size()) {
        uint32_t n = (data[i] << 16) | (data[i + 1] << 8) | data[i + 2];
        out.push_back(kB64Chars[(n >> 18) & 0x3F]);
        out.push_back(kB64Chars[(n >> 12) & 0x3F]);
        out.push_back(kB64Chars[(n >> 6) & 0x3F]);
        out.push_back(kB64Chars[n & 0x3F]);
        i += 3;
    }
    size_t rem = data.size() - i;
    if (rem == 1) {
        uint32_t n = data[i] << 16;
        out.push_back(kB64Chars[(n >> 18) & 0x3F]);
        out.push_back(kB64Chars[(n >> 12) & 0x3F]);
        out.push_back('=');
        out.push_back('=');
    } else if (rem == 2) {
        uint32_t n = (data[i] << 16) | (data[i + 1] << 8);
        out.push_back(kB64Chars[(n >> 18) & 0x3F]);
        out.push_back(kB64Chars[(n >> 12) & 0x3F]);
        out.push_back(kB64Chars[(n >> 6) & 0x3F]);
        out.push_back('=');
    }
    return out;
}

std::vector<uint8_t> base64Decode(std::string const& in) {
    static int8_t table[256];
    static bool   init = false;
    if (!init) {
        std::fill(std::begin(table), std::end(table), -1);
        for (int i = 0; i < 64; ++i) table[static_cast<uint8_t>(kB64Chars[i])] = static_cast<int8_t>(i);
        init = true;
    }
    std::vector<uint8_t> out;
    out.reserve((in.size() / 4) * 3);
    int val = 0, bits = -8;
    for (unsigned char c : in) {
        if (c == '=' || table[c] == -1) continue;
        val = (val << 6) + table[c];
        bits += 6;
        if (bits >= 0) {
            out.push_back(static_cast<uint8_t>((val >> bits) & 0xFF));
            bits -= 8;
        }
    }
    return out;
}

} // namespace

nlohmann::json capeToJson(CapeData const& cape) {
    return nlohmann::json{
        {"format", "cosmos_cape"},
        {"format_version", 1},
        {"name", cape.name},
        {"width", cape.width},
        {"height", cape.height},
        {"pixels", base64Encode(cape.pixelsRGBA)},
    };
}

bool capeFromJson(nlohmann::json const& j, CapeData& out) {
    if (!j.contains("width") || !j.contains("height") || !j.contains("pixels")) return false;
    out.name   = j.value("name", std::string{"CustomCape"});
    out.width  = j.value("width", 0);
    out.height = j.value("height", 0);
    out.pixelsRGBA = base64Decode(j.at("pixels").get<std::string>());
    return out.isValid();
}

bool loadCapeJson(std::filesystem::path const& path, CapeData& out) {
    std::ifstream f(path, std::ios::binary);
    if (!f.is_open()) return false;
    nlohmann::json j;
    try {
        f >> j;
    } catch (...) {
        return false;
    }
    return capeFromJson(j, out);
}

bool saveCapeJson(std::filesystem::path const& path, CapeData const& cape) {
    if (!cape.isValid()) return false;
    std::ofstream f(path, std::ios::binary | std::ios::trunc);
    if (!f.is_open()) return false;
    f << capeToJson(cape).dump(2);
    return true;
}

bool loadCapeFromPng(std::filesystem::path const& path, CapeData& out, std::string const& nameHint) {
    int w = 0, h = 0, channels = 0;
    unsigned char* pixels = stbi_load(path.string().c_str(), &w, &h, &channels, 4 /* force RGBA */);
    if (!pixels) return false;

    out.name   = nameHint.empty() ? path.stem().string() : nameHint;
    out.width  = w;
    out.height = h;
    out.pixelsRGBA.assign(pixels, pixels + (static_cast<size_t>(w) * h * 4));
    stbi_image_free(pixels);
    return out.isValid();
}

bool saveCapeToPng(std::filesystem::path const& path, CapeData const& cape) {
    if (!cape.isValid()) return false;
    return stbi_write_png(
        path.string().c_str(), cape.width, cape.height, 4,
        cape.pixelsRGBA.data(), cape.width * 4
    ) != 0;
}

CapeData downscaleCape(CapeData const& source, int maxW, int maxH) {
    if (!source.isValid()) return source;
    if (source.width <= maxW && source.height <= maxH) return source;

    double scale = std::min(
        static_cast<double>(maxW) / source.width,
        static_cast<double>(maxH) / source.height
    );
    int newW = std::max(1, static_cast<int>(source.width * scale));
    int newH = std::max(1, static_cast<int>(source.height * scale));

    CapeData out;
    out.name   = source.name;
    out.width  = newW;
    out.height = newH;
    out.pixelsRGBA.resize(static_cast<size_t>(newW) * newH * 4);

    for (int y = 0; y < newH; ++y) {
        int srcY = std::min(source.height - 1, static_cast<int>(y / scale));
        for (int x = 0; x < newW; ++x) {
            int srcX = std::min(source.width - 1, static_cast<int>(x / scale));
            size_t srcIdx = (static_cast<size_t>(srcY) * source.width + srcX) * 4;
            size_t dstIdx = (static_cast<size_t>(y) * newW + x) * 4;
            std::copy_n(&source.pixelsRGBA[srcIdx], 4, &out.pixelsRGBA[dstIdx]);
        }
    }
    return out;
}

} // namespace customcape
