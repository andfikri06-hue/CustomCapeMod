#pragma once

#include <filesystem>
#include <functional>
#include <optional>
#include <string>

namespace customcape {

using CanvasSaveCallback = std::function<void(std::string const& base64Rgba, int width, int height, std::string const& name)>;
void setCanvasSaveCallback(CanvasSaveCallback cb);
bool openCapeCanvasEditor();
std::optional<std::filesystem::path> downloadCapeFromUrl(std::string const& url);

}
