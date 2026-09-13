#pragma once

#include <filesystem>
#include <optional>
#include <string>
#include <vector>

#include "CapeData.h"

namespace customcape {

// Where players drop cape files directly from the Android file manager,
// or where this mod writes capes exported from the in-game canvas.
//
// NOTE: verify the exact casing on a real device — Android's shared
// media directory is usually .../Android/media/<package>/ (capital A).
// This is the path given for this project; if LeviLauncher's actual
// folder differs in casing, update this one constant.
inline std::filesystem::path const kCapeStorageDir =
    "/storage/emulated/0/android/media/org.levimc.launcher/customcape/";

struct CapeEntry {
    std::string           displayName;
    std::filesystem::path filePath;
};

class CapeManager {
public:
    static CapeManager& getInstance();

    // Creates kCapeStorageDir if it doesn't exist yet.
    void ensureStorageDir() const;

    // Scans kCapeStorageDir for .json and .png cape files.
    std::vector<CapeEntry> listAvailableCapes() const;

    // Loads a cape by its file path (handles both .json and .png).
    std::optional<CapeData> loadCape(std::filesystem::path const& path) const;

    // Imports an arbitrary image file (e.g. picked via the Android file
    // picker) into the storage folder as a Cosmos-style .json cape.
    bool importImageAsCape(std::filesystem::path const& sourceImage, std::string const& capeName);

    // Saves canvas-drawn pixel data as a new .json cape in the storage folder.
    bool saveDrawnCape(CapeData const& cape);

    // The cape currently equipped by the local player (persisted in the
    // mod's own config so it survives a relaunch).
    std::optional<CapeData> getActiveCape() const;
    bool                    setActiveCape(std::filesystem::path const& path);
    void                    clearActiveCape();

private:
    CapeManager() = default;
    std::filesystem::path configFile() const;
    void                  persistActiveCapePath(std::filesystem::path const& path) const;
};

} // namespace customcape
