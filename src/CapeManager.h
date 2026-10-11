#pragma once

#include <filesystem>
#include <optional>
#include <string>
#include <vector>

#include "CapeData.h"

namespace customcape {

// Where players drop cape files directly from the Android file manager,
// or where this mod writes capes exported from the in-game canvas.
// (Android's shared-storage layer is case-insensitive, so ".../android/media/..."
// and ".../Android/media/..." are the same folder.)
inline std::filesystem::path const kCapeStorageDir =
    "/storage/emulated/0/android/media/org.levimc.launcher/customcape/";

struct CapeEntry {
    std::string           displayName; // file name without extension
    std::filesystem::path filePath;
};

// Turns any text into something safe to use as ONE file name: no slashes, no
// control characters, no leading/trailing dots or spaces, max 48 bytes.
// Falls back to `fallback` (then to "MyCape") when nothing usable is left.
std::string sanitizeCapeName(std::string const& raw, std::string const& fallback = "MyCape");

class CapeManager {
public:
    static CapeManager& getInstance();

    // Creates kCapeStorageDir if it doesn't exist yet.
    void ensureStorageDir() const;

    // Scans kCapeStorageDir for .json and .png cape files (one entry per
    // name, .json wins over .png), sorted by name.
    std::vector<CapeEntry> listAvailableCapes() const;

    // Resolves a name from listAvailableCapes() to its file. Empty path if unknown.
    std::filesystem::path findCapeByName(std::string const& name) const;

    // Loads a cape by its file path (handles both .json and .png).
    std::optional<CapeData> loadCape(std::filesystem::path const& path) const;

    // Imports an image file (PNG/JPG...) into the storage folder as a
    // Cosmos-style .json cape. Returns the written file, empty path on failure.
    std::filesystem::path importImageAsCape(std::filesystem::path const& sourceImage, std::string const& capeName);

    // Saves canvas-drawn pixel data as a new .json cape in the storage folder.
    // Returns the written file, empty path on failure.
    std::filesystem::path saveDrawnCape(CapeData const& cape);

    // The cape currently equipped by the local player (persisted in the
    // mod's own config so it survives a relaunch).
    std::optional<CapeData> getActiveCape() const;
    std::string             activeCapeName() const; // file name without extension, "" if none
    bool                    setActiveCape(std::filesystem::path const& path);
    void                    clearActiveCape();

private:
    CapeManager() = default;
    std::filesystem::path configFile() const;
    std::filesystem::path readActivePath() const;
    bool                  persistActiveCapePath(std::filesystem::path const& path) const;
};

} // namespace customcape
