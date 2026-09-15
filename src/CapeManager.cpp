#include "CapeManager.h"

#include <fstream>

#include <nlohmann/json.hpp>

#include <pl/Mod.hpp>

namespace customcape {

CapeManager& CapeManager::getInstance() {
    static CapeManager instance;
    return instance;
}

void CapeManager::ensureStorageDir() const {
    std::error_code ec;
    std::filesystem::create_directories(kCapeStorageDir, ec);
    // ec is intentionally ignored here — if this fails (e.g. storage
    // permission not yet granted), listAvailableCapes() below just
    // returns an empty list and the config menu explains why.
}

std::vector<CapeEntry> CapeManager::listAvailableCapes() const {
    std::vector<CapeEntry> result;
    std::error_code        ec;
    if (!std::filesystem::exists(kCapeStorageDir, ec)) return result;

    for (auto const& entry : std::filesystem::directory_iterator(kCapeStorageDir, ec)) {
        if (!entry.is_regular_file()) continue;
        auto ext = entry.path().extension().string();
        if (ext == ".json" || ext == ".png") {
            result.push_back(CapeEntry{entry.path().stem().string(), entry.path()});
        }
    }
    return result;
}

std::optional<CapeData> CapeManager::loadCape(std::filesystem::path const& path) const {
    CapeData cape;
    bool     ok = false;

    if (path.extension() == ".json") {
        ok = loadCapeJson(path, cape);
    } else if (path.extension() == ".png") {
        ok = loadCapeFromPng(path, cape, path.stem().string());
    }

    if (!ok) return std::nullopt;
    return cape;
}

bool CapeManager::importImageAsCape(std::filesystem::path const& sourceImage, std::string const& capeName) {
    ensureStorageDir();

    CapeData cape;
    if (!loadCapeFromPng(sourceImage, cape, capeName)) return false;

    // Bedrock Cosmos-style drag-and-drop capes are stored full quality —
    // the canvas-size cap only applies to the in-game drawing tool itself.
    auto destPath = kCapeStorageDir / (capeName + ".json");
    return saveCapeJson(destPath, cape);
}

bool CapeManager::saveDrawnCape(CapeData const& cape) {
    ensureStorageDir();
    if (!cape.isValid()) return false;
    if (cape.width > kCapeCanvasMaxWidth || cape.height > kCapeCanvasMaxHeight) return false;

    auto safeName = cape.name.empty() ? std::string{"MyCape"} : cape.name;
    auto destPath = kCapeStorageDir / (safeName + ".json");
    return saveCapeJson(destPath, cape);
}

std::filesystem::path CapeManager::configFile() const {
    return ll::mod::NativeMod::current()->getConfigDir() / "active_cape.json";
}

std::optional<CapeData> CapeManager::getActiveCape() const {
    std::ifstream f(configFile(), std::ios::binary);
    if (!f.is_open()) return std::nullopt;

    nlohmann::json j;
    try {
        f >> j;
    } catch (...) {
        return std::nullopt;
    }
    if (!j.contains("path")) return std::nullopt;

    return loadCape(std::filesystem::path(j.at("path").get<std::string>()));
}

bool CapeManager::setActiveCape(std::filesystem::path const& path) {
    if (!loadCape(path).has_value()) return false;
    persistActiveCapePath(path);
    return true;
}

void CapeManager::clearActiveCape() {
    std::error_code ec;
    std::filesystem::remove(configFile(), ec);
}

void CapeManager::persistActiveCapePath(std::filesystem::path const& path) const {
    std::filesystem::create_directories(ll::mod::NativeMod::current()->getConfigDir());
    std::ofstream f(configFile(), std::ios::binary | std::ios::trunc);
    if (!f.is_open()) return;
    f << nlohmann::json{{"path", path.string()}}.dump(2);
}

} // namespace customcape
