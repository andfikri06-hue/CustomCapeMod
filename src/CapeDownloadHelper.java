package org.levimc.launcher.customcape;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Downloads a cape from a direct URL (e.g. a Bedrock Cosmos
 * capes.bedrock-cosmos.app/zips/*.zip link) and, if it's a zip,
 * extracts the texture PNG inside it.
 *
 * Deliberately Activity/Context-free (unlike CapeCanvasActivity) - plain
 * networking + java.util.zip only, so it doesn't depend on
 * CapeCanvasBridge's getGameActivity() placeholder being wired up first.
 * Runs synchronously; the native caller (CapeCanvasBridge.cpp) is
 * expected to invoke this from a background std::thread, not the game's
 * main/render thread, since this blocks on network I/O.
 *
 * NOTE: same integration caveat as CapeCanvasActivity.java - this class
 * needs to be compiled into a .dex your LeviLaunchroid build actually
 * loads.
 *
 * NOTE 2: the "which PNG inside the zip is the actual cape texture, not
 * a preview icon" heuristic below (skip filenames containing "icon") is
 * a guess based on how Bedrock content packs are typically structured -
 * not confirmed against a real extracted levicape.zip. If it picks the
 * wrong file, log what's actually inside the zip (unzip -l on a PC is
 * the fastest way) and this heuristic can be tightened.
 */
public class CapeDownloadHelper {

    private static final int TIMEOUT_MS = 15000;

    /**
     * @param urlString direct URL to a .zip or .png cape file.
     * @param destDir   local directory to extract/save into (the mod's
     *                  data dir - caller creates it beforehand).
     * @return absolute path to a local PNG file ready to import, or null
     *         on any failure (network error, no PNG found, etc).
     */
    public static String downloadAndExtractCape(String urlString, String destDir) {
        HttpURLConnection conn = null;
        try {
            File dir = new File(destDir);
            if (!dir.exists() && !dir.mkdirs()) return null;

            URL url = new URL(urlString);
            conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(TIMEOUT_MS);
            conn.setReadTimeout(TIMEOUT_MS);
            conn.setInstanceFollowRedirects(true);
            conn.setRequestProperty("User-Agent", "CustomCapeMod/1.0");

            int code = conn.getResponseCode();
            if (code != HttpURLConnection.HTTP_OK) return null;

            String path     = url.getPath();
            boolean pngHint = path != null && path.toLowerCase().endsWith(".png");

            if (pngHint) {
                File out = new File(dir, "downloaded_cape.png");
                try (InputStream in = conn.getInputStream(); FileOutputStream fos = new FileOutputStream(out)) {
                    copyStream(in, fos);
                }
                return out.getAbsolutePath();
            }

            // Default: treat as a zip - that's what Bedrock Cosmos
            // actually serves (capes.bedrock-cosmos.app/zips/*.zip).
            return extractFirstPngFromZip(conn.getInputStream(), dir);

        } catch (IOException e) {
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static String extractFirstPngFromZip(InputStream rawIn, File destDir) throws IOException {
        String fallbackPath = null;

        try (ZipInputStream zis = new ZipInputStream(rawIn)) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (entry.isDirectory()) continue;

                String lowerName = entry.getName().toLowerCase();
                if (!lowerName.endsWith(".png")) continue;

                boolean looksLikeIcon =
                    lowerName.contains("icon") || lowerName.contains("thumbnail") || lowerName.contains("preview");

                File out = new File(destDir, "downloaded_cape.png");
                try (FileOutputStream fos = new FileOutputStream(out)) {
                    copyStream(zis, fos);
                }

                if (!looksLikeIcon) {
                    // Prefer the first non-icon-looking PNG - most likely
                    // the actual cape texture.
                    return out.getAbsolutePath();
                }
                // Remember it as a fallback in case the zip ONLY contains
                // icon-named PNGs (better than returning null outright).
                fallbackPath = out.getAbsolutePath();
            }
        }
        return fallbackPath;
    }

    private static void copyStream(InputStream in, OutputStream out) throws IOException {
        byte[] buffer = new byte[8192];
        int    read;
        while ((read = in.read(buffer)) > 0) {
            out.write(buffer, 0, read);
        }
    }
}
