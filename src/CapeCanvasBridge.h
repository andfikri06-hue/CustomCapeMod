#pragma once

#include <functional>

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

} // namespace customcape
