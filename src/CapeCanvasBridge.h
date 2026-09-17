#pragma once

#include <filesystem>
#include <functional>
#include <optional>
#include <string>

namespace customcape {

// Called from JNI once the player taps "Save" in the in-game cape canvas.
// Wired to CapeManager::saveDrawnCape(...) from Main.cpp at startup.
using CanvasSaveCallback = std::function<void(std::string const& base64Rgba, int width, int height, std::string const& name)>;
void setCanvasSaveCallback(CanvasSaveCallback cb);

// Opens the drawing canvas (a WebView Activity hosting assets/cape_canvas.html)
// on top of the game. Call from the config Form's "Draw a cape" button.
//
// NOTE — the one LeviLaunchroid-specific unknown here: obtaining the current
// foreground Activity/Context from inside a native mod. getGameActivity()
// below is a placeholder; point it at whatever your other native mods
// already use to grab an Activity (you need the same thing for things like
// showing a Toast, or a system file picker for "add cape from folder").
bool openCapeCanvasEditor();

// Downloads `url` (a direct .zip or .png link - e.g. Bedrock Cosmos's
// capes.bedrock-cosmos.app/zips/*.zip) via CapeDownloadHelper.java and,
// if it's a zip, extracts the texture PNG inside it. Returns a local
// path ready for CapeManager::importImageAsCape(), or std::nullopt on
// failure (network error, no JavaVM attached, no PNG found, etc).
//
// Unlike openCapeCanvasEditor(), this does NOT need getGameActivity() -
// CapeDownloadHelper.java is plain networking + java.util.zip, no
// Activity/Context required.
//
// BLOCKS on network I/O - call from a background std::thread, never
// from the game's main/render thread (see importCapeFromSource() in
// Main.cpp for the calling pattern).
std::optional<std::filesystem::path> downloadCapeFromUrl(std::string const& url);

} // namespace customcape
