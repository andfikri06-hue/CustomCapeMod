# CustomCapeMod — LeviLauncher (LeviLaunchroid)

Import, draw, and share custom capes on Minecraft Bedrock 26.45, built as a
native LeviLamina client mod for LeviLaunchroid.

## What it does

- **`/cape`** in-game opens the Custom Cape menu.
- **Choose from folder** — lists every `.json`/`.png` cape sitting in
  `/storage/emulated/0/android/media/org.levimc.launcher/customcape/`
  (drop files there directly from a file manager, or from the in-game canvas).
- **Draw a new cape** — opens a full pixel-art editor (brush, eraser, bucket
  fill, color picker, unlimited colors, undo/redo, PNG import/export) as a
  WebView on top of the game, up to 640×320. Saves straight into the
  capes folder as a Bedrock-Cosmos-style JSON.
- **Cross-player visibility** — the equipped cape is written into the local
  player's own outgoing skin data (so *any* nearby player sees it, mod or
  not), and a small custom packet additionally broadcasts the full-res
  source art to other CustomCapeMod users for a sharper live preview.

## Architecture

```
ConfigMenu ──┬── CapeManager ──── CapeData (JSON ⇄ PNG, downscale)
             │        │
             │        └── kCapeStorageDir (the Android folder)
             │
             ├── CapeCanvasBridge (JNI) ── CapeCanvasActivity.java ── cape_canvas.html
             │
             ├── SkinBridge ── hooks the local player's skin serialize step
             │
             └── CapeSyncPacket ── ll::network custom packet, broadcast on equip/clear
```

## Before you build: three things to confirm on your actual headers

Everything in this mod is built against LeviLamina's **documented** `ll::`
API (Mod, Form, Event, Network, Command, Memory & Hook) — verified against
the current docs at lamina.levimc.org while writing this. Three pieces
reach past that documented surface into version-specific internals and are
clearly marked in the code with `PLACEHOLDER` / `TODO` comments:

1. **`SkinBridge.cpp`** — the hooked class/method (`LocalPlayer::sendLocalSkin`)
   and the `SerializedSkin` cape field names are stand-ins. Use the
   **Find Function Guide** to locate the real function that builds the
   local player's skin before it's sent, and the **Hook Guide** to wire
   `LL_AUTO_INSTANCE_HOOK` to it (the macro itself is real and already
   used correctly here — only the target symbol needs swapping).
2. **`CapeCanvasBridge.cpp`** — `getGameActivity()` is a stub. Point it at
   whatever your other native mods already use to get an
   `android.app.Activity`/`Context` reference. Also see the comment above
   `JNI_OnLoad`: if LeviLamina loads this `.so` via `dlopen` rather than
   `System.loadLibrary`, `JNI_OnLoad` won't fire, and you'll need to pull
   the game's existing `JavaVM*` from wherever LeviLaunchroid's own native
   layer caches it.
3. **`Main.cpp` → `registerSyncPacket()`** — the exact call to bind
   `CapeSyncPacket` to `CapeSyncHandler` wasn't in the page this was
   checked against; confirm the registration function name in
   `ll/api/network/`.

Nothing else in the mod depends on unverified internals — the menu, file
handling, cape format, command, and packet *definition* will compile and
behave as written once these three are filled in.

## Build

Follows the standard LeviLamina mod convention (`xmake.lua` + the
`levilamina` package). The Android/NDK cross-compile flags in
`.github/workflows/build.yml` are a starting point — since your other
LeviLaunchroid mods already build successfully via GitHub Actions, copy
the exact NDK version and `xmake f -p android ...` line from one of those
working workflows instead; it's already proven against your toolchain.

You'll also need to vendor two small single-header libraries into `src/`
(both public domain, used for PNG encode/decode in `CapeData.cpp`):
[`stb_image.h`](https://github.com/nothings/stb/blob/master/stb_image.h) and
[`stb_image_write.h`](https://github.com/nothings/stb/blob/master/stb_image_write.h).

## Cape JSON format

```json
{
  "format": "cosmos_cape",
  "format_version": 1,
  "name": "MyCape",
  "width": 64,
  "height": 32,
  "pixels": "<base64 RGBA8 bytes, width*height*4 long>"
}
```

`.png` files dropped in the folder work too — they're read straight into
the same in-memory `CapeData` shape.
