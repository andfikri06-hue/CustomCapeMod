#include "PixelCanvas.h"

#include <algorithm>
#include <array>
#include <cstdint>
#include <mutex>
#include <vector>

#include <pl/Mod.hpp>
#include <pl/ModMenu.hpp>

#include "CapeData.h"
#include "CapeManager.h"
#include "SkinBridge.h"

namespace customcape {

namespace {

constexpr int kCanvasW = 16;
constexpr int kCanvasH = 8;
constexpr int kUpscale = 4; // 16*4=64, 8*4=32 - lands exactly on the standard cape size

// Small fixed palette, cycled via the "Color" button. Stored as
// 0xRRGGBBAA to match this codebase's own CapeData::pixelsRGBA byte
// order (see CapeData.h) - used as-is for the final saved cape.
//
// NOT CONFIRMED: whether pl::modmenu::DrawCommand::color and
// ButtonInfo's *BgColor fields expect that same RRGGBBAA order, or
// something else (ARGB is also common in overlay/UI APIs). If the
// on-screen grid/button colors look swapped or wrong on a real device,
// this byte order - not the saved cape's, which is independently
// correct - is the first thing to adjust.
constexpr std::array<uint32_t, 10> kPalette = {
    0x000000FFu, // black
    0xFFFFFFFFu, // white
    0xFF0000FFu, // red
    0xFF7F00FFu, // orange
    0xFFFF00FFu, // yellow
    0x00FF00FFu, // green
    0x0000FFFFu, // blue
    0x7F00FFFFu, // purple
    0x7F4B2FFFu, // brown
    0x00000000u, // transparent / eraser
};

std::mutex                                gCanvasMutex;
std::array<uint32_t, kCanvasW * kCanvasH> gCells{}; // zero-init = fully transparent
int                                        gCursorX      = 0;
int                                        gCursorY      = 0;
size_t                                     gPaletteIndex = 0;

uint32_t currentColor() { return kPalette[gPaletteIndex]; }

void redraw() {
    auto size = pl::modmenu::getHudSurfaceSize();

    // Grid sized to ~40% of the shorter screen dimension, anchored
    // near the top-left. The D-pad/paint/color/save/clear buttons will
    // likely get dragged elsewhere by the player via the HUD editor
    // (see HudEditorElement in ModMenu.hpp) if this crowds them.
    float shortSide = std::min(size.width, size.height);
    float cell       = (shortSide * 0.4f) / static_cast<float>(std::max(kCanvasW, kCanvasH));
    float originX    = 24.0f;
    float originY    = 24.0f;

    std::vector<pl::modmenu::DrawCommand> commands;
    commands.reserve(static_cast<size_t>(kCanvasW) * kCanvasH + 1);

    std::lock_guard<std::mutex> lock(gCanvasMutex);
    for (int y = 0; y < kCanvasH; ++y) {
        for (int x = 0; x < kCanvasW; ++x) {
            pl::modmenu::DrawCommand cmd{};
            cmd.type  = pl::modmenu::DrawCommandType::RectFilled;
            cmd.x     = originX + x * cell;
            cmd.y     = originY + y * cell;
            cmd.w     = cell - 1.0f; // 1px gap so cells read as a grid
            cmd.h     = cell - 1.0f;
            cmd.color = gCells[y * kCanvasW + x];
            commands.push_back(cmd);
        }
    }

    // Cursor outline drawn last, on top of the grid.
    pl::modmenu::DrawCommand cursor{};
    cursor.type  = pl::modmenu::DrawCommandType::Rect;
    cursor.x     = originX + gCursorX * cell;
    cursor.y     = originY + gCursorY * cell;
    cursor.w     = cell - 1.0f;
    cursor.h     = cell - 1.0f;
    cursor.color = 0xFF00FFFFu; // cyan - reads against both light and dark cells
    commands.push_back(cursor);

    pl::modmenu::submitDrawCommands("customcape", commands);
}

void moveCursor(int dx, int dy) {
    std::lock_guard<std::mutex> lock(gCanvasMutex);
    gCursorX = (gCursorX + dx + kCanvasW) % kCanvasW;
    gCursorY = (gCursorY + dy + kCanvasH) % kCanvasH;
}

void paintAtCursor() {
    std::lock_guard<std::mutex> lock(gCanvasMutex);
    gCells[gCursorY * kCanvasW + gCursorX] = currentColor();
}

void clearCanvas() {
    std::lock_guard<std::mutex> lock(gCanvasMutex);
    gCells.fill(0);
}

// Forward-declared so cycleColor() can re-register itself with updated
// swatch colors (see below).
void cycleColor();

void registerColorButton() {
    pl::modmenu::ButtonBuilder("customcape_color", "Color")
        .moduleId("customcape")
        .styleColors(currentColor(), currentColor())
        .onEvent([](std::string_view, pl::modmenu::ButtonEvent event, float) {
            if (event == pl::modmenu::ButtonEvent::Click) cycleColor();
        })
        .registerButton();
}

void cycleColor() {
    gPaletteIndex = (gPaletteIndex + 1) % kPalette.size();
    // Re-registering the same buttonId updates it in place (per
    // registerModule()/registerButton() semantics - both take the full
    // Info struct and are meant to be called again to update) - used
    // here purely so the "Color" button's own background shows what
    // you're currently painting with.
    registerColorButton();
}

void saveCanvasAsCape() {
    CapeData cape;
    cape.name   = "PixelCape";
    cape.width  = kCanvasW * kUpscale;
    cape.height = kCanvasH * kUpscale;
    cape.pixelsRGBA.resize(static_cast<size_t>(cape.width) * cape.height * 4);

    {
        std::lock_guard<std::mutex> lock(gCanvasMutex);
        for (int y = 0; y < cape.height; ++y) {
            for (int x = 0; x < cape.width; ++x) {
                uint32_t px    = gCells[(y / kUpscale) * kCanvasW + (x / kUpscale)];
                size_t   pixel = (static_cast<size_t>(y) * cape.width + x) * 4;
                cape.pixelsRGBA[pixel + 0] = static_cast<uint8_t>((px >> 24) & 0xFF);
                cape.pixelsRGBA[pixel + 1] = static_cast<uint8_t>((px >> 16) & 0xFF);
                cape.pixelsRGBA[pixel + 2] = static_cast<uint8_t>((px >> 8) & 0xFF);
                cape.pixelsRGBA[pixel + 3] = static_cast<uint8_t>(px & 0xFF);
            }
        }
    }

    auto& log = ll::mod::NativeMod::current()->getLogger();
    if (CapeManager::getInstance().saveDrawnCape(cape)) {
        CapeManager::getInstance().setActiveCape(kCapeStorageDir / (cape.name + ".json"));
        applyCapeToLocalPlayer(cape);
        log.info("Saved and equipped: {}", cape.name);
    } else {
        log.error("Couldn't save the pixel canvas as a cape.");
    }
}

} // namespace

void registerPixelCanvasButtons() {
    pl::modmenu::ButtonBuilder("customcape_up", "^")
        .moduleId("customcape")
        .onEvent([](std::string_view, pl::modmenu::ButtonEvent event, float) {
            if (event == pl::modmenu::ButtonEvent::Click) {
                moveCursor(0, -1);
                redraw();
            }
        })
        .registerButton();

    pl::modmenu::ButtonBuilder("customcape_down", "v")
        .moduleId("customcape")
        .onEvent([](std::string_view, pl::modmenu::ButtonEvent event, float) {
            if (event == pl::modmenu::ButtonEvent::Click) {
                moveCursor(0, 1);
                redraw();
            }
        })
        .registerButton();

    pl::modmenu::ButtonBuilder("customcape_left", "<")
        .moduleId("customcape")
        .onEvent([](std::string_view, pl::modmenu::ButtonEvent event, float) {
            if (event == pl::modmenu::ButtonEvent::Click) {
                moveCursor(-1, 0);
                redraw();
            }
        })
        .registerButton();

    pl::modmenu::ButtonBuilder("customcape_right", ">")
        .moduleId("customcape")
        .onEvent([](std::string_view, pl::modmenu::ButtonEvent event, float) {
            if (event == pl::modmenu::ButtonEvent::Click) {
                moveCursor(1, 0);
                redraw();
            }
        })
        .registerButton();

    pl::modmenu::ButtonBuilder("customcape_paint", "Paint")
        .moduleId("customcape")
        .onEvent([](std::string_view, pl::modmenu::ButtonEvent event, float) {
            if (event == pl::modmenu::ButtonEvent::Click) {
                paintAtCursor();
                redraw();
            }
        })
        .registerButton();

    registerColorButton();

    pl::modmenu::ButtonBuilder("customcape_clear", "Clear")
        .moduleId("customcape")
        .onEvent([](std::string_view, pl::modmenu::ButtonEvent event, float) {
            if (event == pl::modmenu::ButtonEvent::Click) {
                clearCanvas();
                redraw();
            }
        })
        .registerButton();

    pl::modmenu::ButtonBuilder("customcape_save", "Save")
        .moduleId("customcape")
        .onEvent([](std::string_view, pl::modmenu::ButtonEvent event, float) {
            if (event == pl::modmenu::ButtonEvent::Click) saveCanvasAsCape();
        })
        .registerButton();

    redraw();
}

void unregisterPixelCanvasButtons() {
    pl::modmenu::unregisterButton("customcape_up");
    pl::modmenu::unregisterButton("customcape_down");
    pl::modmenu::unregisterButton("customcape_left");
    pl::modmenu::unregisterButton("customcape_right");
    pl::modmenu::unregisterButton("customcape_paint");
    pl::modmenu::unregisterButton("customcape_color");
    pl::modmenu::unregisterButton("customcape_clear");
    pl::modmenu::unregisterButton("customcape_save");
}

} // namespace customcape
