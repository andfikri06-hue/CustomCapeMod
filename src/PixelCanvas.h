#pragma once

namespace customcape {

// A small pixel-art cape editor rendered entirely inside the "Custom
// Cape" Mod Menu module's own HUD overlay (pl::modmenu::submitDrawCommands),
// controlled by a handful of Mod Menu buttons (D-pad + paint + color +
// save + clear). No separate Activity, no WebView, no JNI Activity
// lookup - which is exactly the code path that was crashing in
// openCapeCanvasEditor() (see the tombstone: null/invalid Activity
// reference inside that JNI dance). Everything here uses only the
// confirmed pl::modmenu:: primitives (ModuleBuilder/ButtonBuilder,
// DrawCommand/submitDrawCommands), so it's the "canvas inside the
// config menu, not outside it" version.
//
// Deliberate scope limit: works at 16x8 "cells", upscaled 4x4 (nearest
// neighbor) to the standard 64x32 cape texture size on save. That's a
// real trade-off versus the old freeform WebView canvas - fine for
// simple/blocky designs navigated by D-pad, not for detailed art. For
// anything more detailed, the existing "paste a local file path or a
// Bedrock Cosmos link" config field (see Main.cpp's cosmos_source
// field) is still the better path - this editor and that field are
// complementary, not a replacement for each other.
//
// Call registerPixelCanvasButtons() once from CustomCapeMod::enable(),
// after the module itself is registered. Call
// unregisterPixelCanvasButtons() from disable().
void registerPixelCanvasButtons();
void unregisterPixelCanvasButtons();

} // namespace customcape
