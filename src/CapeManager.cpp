#include "CapeManager.h"

#include <algorithm>
#include <cctype>
#include <fstream>
#include <map>

#include <nlohmann/json.hpp>

#include "Runtime.h"

namespace customcape {

namespace {

std::string lower(std::string s) {
    std::transform(s.begin(), s.end(), s.begin(), [](unsigned char c) { return static_cast<char>(std::tolower(c)); });
    return s;
}

bool isContinuationByte(char c) { return (static_cast<unsigned char>(c) & 0xC0) == 0x80; }

std::string sanitizeOnce(std::string const& raw) {
    std::string out;
    out.reserve(raw.size());
    for (unsigned char c : raw) {
        bool keep = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == ' ' || c == '-'
                 || c == '_' || c == '.' || c == '(' || c == ')'
                 || c >= 0x80; // UTF-8 letters are fine in file names
        out.push_back(keep ? static_cast<char>(c) : '_');
    }

    // cut at 48 bytes without splitting a UTF-8 character
    constexpr size_t kMaxBytes = 48;
    if (out.size() > kMaxBytes) {
        size_t cut = kMaxBytes;
        while (cut > 0 && isContinuationByte(out[cut])) --cut;
        out.resize(cut);
    }

    // no leading / trailing dots or spaces ("..", ".hidden", "name " ...)
    size_t b = out.find_first_not_of(" .");
    if (b == std::string::npos) return {};
    size_t e = out.find_last_not_of(" .");
    return out.substr(b, e - b + 1);
}

} // namespace

std::string sanitizeCapeName(std::string const& raw, std::string const& fallback) {
    std::string name = sanitizeOnce(raw);
    if (!name.empty()) return name;
    name = sanitizeOnce(fallback);
    if (!name.empty()) return name;
    return "MyCape";
}

CapeManager& CapeManager::getInstance() {
    static CapeManager instance;
    return instance;
}

void CapeManager::ensureStorageDir() const {
    std::error_code ec;
    std::filesystem::create_directories(kCapeStorageDir, ec);
    // ec is intentionally ignored here - if this fails (e.g. storage
    // permission not yet granted), listAvailableCapes() below just
    // returns an empty list.
}

std::vector<CapeEntry> CapeManager::listAvailableCapes() const {
    std::error_code ec;
    if (!std::filesystem::is_directory(kCapeStorageDir, ec)) return {};

    // one entry per name; a .json beats a .png with the same name
    std::map<std::string, std::filesystem::path> byName;

    std::filesystem::directory_iterator it(kCapeStorageDir, ec);
    std::filesystem::directory_iterator end;
    while (!ec && it != end) {
        std::error_code ec2;
        if (it->is_regular_file(ec2) && !ec2) {
            std::filesystem::path const& p   = it->path();
            std::string                  ext = lower(p.extension().string());
            std::string                  stem = p.stem().string();
            bool usableName = !stem.empty() && stem.find('\n') == std::string::npos
                           && stem.find('\r') == std::string::npos;
            if (usableName && (ext == ".json" || ext == ".png")) {
                auto found = byName.find(stem);
                if (found == byName.end()) {
                    byName.emplace(stem, p);
                } else if (ext == ".json") {
                    found->second = p;
                }
            }
        }
        it.increment(ec);
    }

    std::vector<CapeEntry> result;
    result.reserve(byName.size());
    for (auto& [name, path] : byName) result.push_back(CapeEntry{name, path});

    std::sort(result.begin(), result.end(), [](CapeEntry const& a, CapeEntry const& b) {
        std::string la = lower(a.displayName), lb = lower(b.displayName);
        return la != lb ? la < lb : a.displayName < b.displayName;
    });
    return result;
}

std::filesystem::path CapeManager::findCapeByName(std::string const& name) const {
    if (name.empty()) return {};
    for (auto const& entry : listAvailableCapes()) {
        if (entry.displayName == name) return entry.filePath;
    }
    return {};
}

std::optional<CapeData> CapeManager::loadCape(std::filesystem::path const& path) const {
    try {
        CapeData    cape;
        bool        ok  = false;
        std::string ext = lower(path.extension().string());

        if (ext == ".json") {
            ok = loadCapeJson(path, cape);
        } else if (ext == ".png") {
            ok = loadCapeFromPng(path, cape, path.stem().string());
        }

        if (!ok) return std::nullopt;
        return cape;
    } catch (std::exception const& ex) {
        rt::error(std::string("loadCape failed: ") + ex.what());
    } catch (...) {
        rt::error("loadCape failed: unknown exception");
    }
    return std::nullopt;
}

std::filesystem::path CapeManager::importImageAsCape(std::filesystem::path const& sourceImage, std::string const& capeName) {
    ensureStorageDir();

    std::string safe = sanitizeCapeName(capeName, sourceImage.stem().string());

    CapeData cape;
    if (!loadCapeFromPng(sourceImage, cape, safe)) return {};
    if (cape.width > 2048 || cape.height > 2048) return {}; // would not fit back into a .json we can reload

    // Bedrock Cosmos-style capes are stored full quality - the canvas-size cap
    // only applies to the in-game drawing tool itself.
    auto destPath = kCapeStorageDir / (safe + ".json");
    if (!saveCapeJson(destPath, cape)) return {};
    return destPath;
}

std::filesystem::path CapeManager::saveDrawnCape(CapeData const& cape) {
    ensureStorageDir();
    if (!cape.isValid()) return {};
    if (cape.width > kCapeCanvasMaxWidth || cape.height > kCapeCanvasMaxHeight) return {};

    CapeData copy = cape;
    copy.name     = sanitizeCapeName(cape.name, "MyCape");

    auto destPath = kCapeStorageDir / (copy.name + ".json");
    if (!saveCapeJson(destPath, copy)) return {};
    return destPath;
}

std::filesystem::path CapeManager::configFile() const {
    auto dir = rt::configDir();
    if (dir.empty()) return {}; // runtime not initialised: refuse instead of writing into the CWD
    return dir / "active_cape.json";
}

std::filesystem::path CapeManager::readActivePath() const {
    auto file = configFile();
    if (file.empty()) return {};

    std::ifstream f(file, std::ios::binary);
    if (!f.is_open()) return {};

    try {
        nlohmann::json j;
        f >> j;
        if (!j.is_object() || !j.contains("path") || !j.at("path").is_string()) return {};
        return std::filesystem::path(j.at("path").get<std::string>());
    } catch (...) {
        return {};
    }
}

std::optional<CapeData> CapeManager::getActiveCape() const {
    auto path = readActivePath();
    if (path.empty()) return std::nullopt;
    return loadCape(path);
}

std::string CapeManager::activeCapeName() const {
    auto path = readActivePath();
    if (path.empty()) return {};
    std::error_code ec;
    if (!std::filesystem::is_regular_file(path, ec)) return {};
    return path.stem().string();
}

bool CapeManager::setActiveCape(std::filesystem::path const& path) {
    if (!loadCape(path).has_value()) return false;
    return persistActiveCapePath(path);
}

void CapeManager::clearActiveCape() {
    auto file = configFile();
    if (file.empty()) return;
    std::error_code ec;
    std::filesystem::remove(file, ec);
}

bool CapeManager::persistActiveCapePath(std::filesystem::path const& path) const {
    auto file = configFile();
    if (file.empty()) return false;

    std::error_code ec;
    std::filesystem::create_directories(file.parent_path(), ec);

    std::ofstream f(file, std::ios::binary | std::ios::trunc);
    if (!f.is_open()) return false;
    f << nlohmann::json{{"path", path.string()}}.dump(2);
    return static_cast<bool>(f);
}

} // namespace customcape
