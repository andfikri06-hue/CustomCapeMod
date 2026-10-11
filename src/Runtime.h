#pragma once

// Process-wide facts about this mod, copied ONCE from the preloader's
// ModContext (inside load()/enable()).
//
// Why this file exists: pl::mod::NativeMod::current() is a thread_local that
// is only set while the preloader is inside load()/enable()/disable()/unload().
// Anywhere else - a Java thread calling into us through JNI, a worker thread,
// a hook - it is nullptr, and dereferencing it kills the whole game. So
// nothing in this mod calls NativeMod::current(); everything goes through rt::.

#include <filesystem>
#include <string>

#include <jni.h>

#include <pl/Mod.hpp>

namespace customcape::rt {

// Idempotent. Must be called from a lifecycle callback (load/enable/...).
void init(pl::mod::ModContext& ctx);

bool ready();

JavaVM*               javaVm();
std::filesystem::path configDir();
std::filesystem::path dataDir();

// Writes to logcat (tag = mod name) AND appends one line to
// <capes folder>/customcape_log.txt, so problems can be read from the phone's
// file manager without a PC. Safe to call from any thread, never throws.
void info(std::string const& msg);
void warn(std::string const& msg);
void error(std::string const& msg);

} // namespace customcape::rt
