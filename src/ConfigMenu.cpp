#include "ConfigMenu.h"

#include <exception>
#include <filesystem>

#include "CapeData.h"
#include "CapeManager.h"
#include "Runtime.h"
#include "SkinBridge.h"

namespace customcape {

namespace {

std::string ok(std::string const& text) { return "OK: " + text; }
std::string err(std::string const& text) { return "ERR: " + text; }

// Honest reminder shown with every "equipped" message until the skin hook exists.
std::string hookNote() {
    return skinHookInstalled() ? std::string{} : std::string{" (Hook skin belum aktif: belum tampil di karakter.)"};
}

// Loads `path`, makes it the active cape and hands it to the skin bridge.
std::string equipFile(std::filesystem::path const& path, std::string const& shownName) {
    auto& mgr  = CapeManager::getInstance();
    auto  cape = mgr.loadCape(path);
    if (!cape) return err("File cape rusak atau bukan cape yang valid: " + shownName);
    if (!mgr.setActiveCape(path)) return err("Gagal menyimpan pilihan cape (config tidak bisa ditulis).");

    applyCapeToLocalPlayer(*cape);
    rt::info("equipped cape: " + shownName + " (" + std::to_string(cape->width) + "x" + std::to_string(cape->height) + ")");
    return ok(shownName + " dipilih." + hookNote());
}

// Every public function funnels through here so no exception can ever reach JNI.
template <typename F>
std::string safely(char const* what, F&& body) {
    try {
        return body();
    } catch (std::exception const& ex) {
        rt::error(std::string(what) + " threw: " + ex.what());
        return err(std::string("Terjadi error: ") + ex.what());
    } catch (...) {
        rt::error(std::string(what) + " threw an unknown exception");
        return err("Terjadi error yang tidak dikenal.");
    }
}

} // namespace

std::string listCapesText() {
    try {
        std::string out;
        for (auto const& entry : CapeManager::getInstance().listAvailableCapes()) {
            if (!out.empty()) out += '\n';
            out += entry.displayName;
        }
        return out;
    } catch (...) {
        return {};
    }
}

std::string activeCapeText() {
    try {
        return CapeManager::getInstance().activeCapeName();
    } catch (...) {
        return {};
    }
}

std::string equipCapeByName(std::string const& name) {
    return safely("equipCapeByName", [&]() -> std::string {
        auto path = CapeManager::getInstance().findCapeByName(name);
        if (path.empty()) return err("Cape tidak ditemukan: " + name);
        return equipFile(path, name);
    });
}

std::string removeActiveCape() {
    return safely("removeActiveCape", []() -> std::string {
        CapeManager::getInstance().clearActiveCape();
        clearCapeFromLocalPlayer();
        rt::info("cape removed");
        return ok("Cape dilepas.");
    });
}

std::string importPngAsCape(std::string const& pngPath, std::string const& name) {
    return safely("importPngAsCape", [&]() -> std::string {
        std::error_code ec;
        if (!std::filesystem::is_regular_file(std::filesystem::path(pngPath), ec)) return err("File tidak ditemukan.");

        auto dest = CapeManager::getInstance().importImageAsCape(std::filesystem::path(pngPath), name);
        if (dest.empty()) return err("Gambar tidak bisa dibaca atau terlalu besar (pakai PNG, maks 2048 px).");
        return equipFile(dest, dest.stem().string());
    });
}

std::string saveDrawnPixels(std::vector<uint8_t> const& rgba, int width, int height, std::string const& name) {
    return safely("saveDrawnPixels", [&]() -> std::string {
        CapeData cape;
        cape.name       = name;
        cape.width      = width;
        cape.height     = height;
        cape.pixelsRGBA = rgba;
        if (!cape.isValid()) return err("Data gambar tidak valid.");

        auto dest = CapeManager::getInstance().saveDrawnCape(cape);
        if (dest.empty()) return err("Gagal menyimpan gambar ke folder customcape (izin penyimpanan?).");
        return equipFile(dest, dest.stem().string());
    });
}

} // namespace customcape
