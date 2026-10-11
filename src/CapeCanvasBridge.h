#pragma once

namespace customcape {

// Loads the Java half of the UI (CapePauseOverlay + helpers) from the dex that
// is embedded in this .so, binds its native methods, and starts the watcher
// that shows the floating Custom Cape panel ONLY while Minecraft's vanilla
// pause menu is open.
//
// Call it from enable(): that runs inside a Java->native frame, which is what
// lets JNI find the launcher's own classes. Returns false (after logging the
// reason to logcat and customcape_log.txt) if anything failed; it never throws.
bool startPauseOverlay();

// Stops the watcher and removes the panel. Safe to call when it never started.
void stopPauseOverlay();

} // namespace customcape
