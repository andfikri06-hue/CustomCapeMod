#include "Runtime.h"

#include <android/log.h>

#include <atomic>
#include <ctime>
#include <fstream>
#include <mutex>

#include "CapeManager.h" // kCapeStorageDir: the log file lives next to the capes

namespace customcape::rt {

namespace {

std::mutex        gInitMutex;
std::atomic<bool> gReady{false};

JavaVM*                  gVm     = nullptr;
pl::log::Logger*         gLogger = nullptr;
std::filesystem::path    gConfigDir;
std::filesystem::path    gDataDir;

std::mutex gLogMutex;

enum class Level { Info, Warn, Error };

void appendToFile(char const* tag, std::string const& msg) noexcept {
    try {
        std::lock_guard lock(gLogMutex);

        std::error_code ec;
        std::filesystem::create_directories(kCapeStorageDir, ec);
        auto file = kCapeStorageDir / "customcape_log.txt";

        auto size = std::filesystem::file_size(file, ec);
        if (!ec && size > 100 * 1024) std::filesystem::remove(file, ec); // start over, never grow forever

        std::ofstream out(file, std::ios::app);
        if (!out) return;

        std::time_t now = std::time(nullptr);
        std::tm     tmv{};
        localtime_r(&now, &tmv);
        char stamp[32] = {0};
        std::strftime(stamp, sizeof(stamp), "%m-%d %H:%M:%S", &tmv);

        out << stamp << " [native] " << tag << ": " << msg << '\n';
    } catch (...) {
        // logging must never take the game down
    }
}

void emit(Level level, std::string const& msg) noexcept {
    try {
        if (gLogger) {
            switch (level) {
            case Level::Info:  gLogger->info("{}", msg); break;
            case Level::Warn:  gLogger->warn("{}", msg); break;
            case Level::Error: gLogger->error("{}", msg); break;
            }
        } else {
            int prio = level == Level::Info ? ANDROID_LOG_INFO : level == Level::Warn ? ANDROID_LOG_WARN : ANDROID_LOG_ERROR;
            __android_log_print(prio, "CustomCapeMod", "%s", msg.c_str());
        }
    } catch (...) {
    }
    appendToFile(level == Level::Info ? "info" : level == Level::Warn ? "warn" : "ERROR", msg);
}

} // namespace

void init(pl::mod::ModContext& ctx) {
    std::lock_guard lock(gInitMutex);
    if (gReady.load()) return; // values never change, so later calls are no-ops

    gVm        = ctx.javaVm();
    gLogger    = &ctx.logger();
    gConfigDir = ctx.configDir();
    gDataDir   = ctx.dataDir();
    gReady.store(true);
}

bool ready() { return gReady.load(); }

JavaVM* javaVm() { return gVm; }

std::filesystem::path configDir() { return gConfigDir; }

std::filesystem::path dataDir() { return gDataDir; }

void info(std::string const& msg) { emit(Level::Info, msg); }

void warn(std::string const& msg) { emit(Level::Warn, msg); }

void error(std::string const& msg) { emit(Level::Error, msg); }

} // namespace customcape::rt
