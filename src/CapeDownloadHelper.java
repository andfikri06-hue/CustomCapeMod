package org.levimc.launcher.customcape;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Downloads a cape from a direct URL (e.g. a Bedrock Cosmos
 * capes.bedrock-cosmos.app/zips/*.zip link) and returns a local PNG.
 *
 * What the link may serve: a .zip (the texture PNG inside is extracted) or a
 * plain .png. The kind is decided from the first bytes of the response, not
 * from the URL, so links without a file extension work too.
 *
 * Plain networking + java.util.zip only (no Activity / Context). It blocks, so
 * the caller (CapePauseOverlay) runs it on its background thread.
 *
 * The link is untrusted input, so: only http(s), a hard size cap on the whole
 * download and on every extracted file, a cap on zip entries, and zip entry
 * names are never used as file names (the output is always downloaded_cape.png).
 */
public class CapeDownloadHelper {

    private static final int  TIMEOUT_MS         = 15000;
    private static final long MAX_DOWNLOAD_BYTES = 16L * 1024 * 1024; // whole response
    private static final long MAX_PNG_BYTES      = 8L * 1024 * 1024;  // one extracted texture
    private static final int  MAX_ZIP_ENTRIES    = 300;

    /** Short reason for the last failure, shown in the panel. Empty after a success. */
    public static volatile String lastError = "";

    /**
     * @param urlString direct URL to a .zip or .png cape file.
     * @param destDir   local directory to extract/save into (created if missing).
     * @return absolute path to a local PNG file ready to import, or null on
     *         any failure (the reason is in {@link #lastError}).
     */
    public static String downloadAndExtractCape(String urlString, String destDir) {
        lastError = "";
        HttpURLConnection conn = null;
        try {
            File dir = new File(destDir);
            if (!dir.exists() && !dir.mkdirs()) {
                lastError = "Folder sementara tidak bisa dibuat.";
                return null;
            }

            URL url = new URL(urlString == null ? "" : urlString.trim());
            String proto = url.getProtocol();
            if (!"https".equalsIgnoreCase(proto) && !"http".equalsIgnoreCase(proto)) {
                lastError = "Link harus diawali https:// atau http://";
                return null;
            }

            conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(TIMEOUT_MS);
            conn.setReadTimeout(TIMEOUT_MS);
            conn.setInstanceFollowRedirects(true);
            conn.setRequestProperty("User-Agent", "CustomCapeMod/1.1");

            int code = conn.getResponseCode();
            if (code != HttpURLConnection.HTTP_OK) {
                lastError = "Server menjawab HTTP " + code + " (cek link-nya).";
                return null;
            }
            if (conn.getContentLength() > MAX_DOWNLOAD_BYTES) {
                lastError = "File terlalu besar untuk sebuah cape.";
                return null;
            }

            InputStream in = new BufferedInputStream(
                    new LimitedInputStream(conn.getInputStream(), MAX_DOWNLOAD_BYTES), 16 * 1024);
            try {
                in.mark(16);
                byte[] head = new byte[4];
                int got = readUpTo(in, head);
                in.reset();

                if (got == 4 && head[0] == (byte) 0x89 && head[1] == 'P' && head[2] == 'N' && head[3] == 'G') {
                    return savePng(in, new File(dir, "downloaded_cape.png"));
                }
                if (got == 4 && head[0] == 'P' && head[1] == 'K') {
                    return extractFirstPngFromZip(in, dir);
                }
                lastError = "Isi link bukan file PNG atau ZIP.";
                return null;
            } finally {
                try {
                    in.close();
                } catch (IOException ignored) {
                }
            }
        } catch (IOException e) {
            lastError = "Gagal mengunduh: " + shortMessage(e);
            return null;
        } catch (RuntimeException e) {
            lastError = "Link tidak valid: " + shortMessage(e);
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static String savePng(InputStream in, File out) throws IOException {
        if (!copyLimited(in, out, MAX_PNG_BYTES)) {
            lastError = "Gambar terlalu besar untuk sebuah cape.";
            return null;
        }
        return out.getAbsolutePath();
    }

    private static String extractFirstPngFromZip(InputStream rawIn, File destDir) throws IOException {
        String fallbackPath = null;
        File out = new File(destDir, "downloaded_cape.png");
        File tmp = new File(destDir, "downloaded_cape.tmp");

        ZipInputStream zis = new ZipInputStream(rawIn);
        try {
            ZipEntry entry;
            int count = 0;
            while ((entry = zis.getNextEntry()) != null) {
                if (++count > MAX_ZIP_ENTRIES) break;
                if (entry.isDirectory()) continue;

                String lowerName = entry.getName().toLowerCase(Locale.ROOT);
                if (!lowerName.endsWith(".png")) continue;

                boolean looksLikeIcon =
                        lowerName.contains("icon") || lowerName.contains("thumbnail") || lowerName.contains("preview");

                // Extract to a temp file first so a bad / oversized entry never replaces a good one.
                if (!copyLimited(zis, tmp, MAX_PNG_BYTES)) continue;

                if (!looksLikeIcon) {
                    // First non-icon PNG: most likely the real cape texture.
                    replace(tmp, out);
                    return out.getAbsolutePath();
                }
                // Only icon-named PNGs so far: keep as a fallback (better than failing).
                File fb = new File(destDir, "downloaded_cape_fallback.png");
                replace(tmp, fb);
                fallbackPath = fb.getAbsolutePath();
            }
        } finally {
            tmp.delete();
            try {
                zis.close();
            } catch (IOException ignored) {
            }
        }
        if (fallbackPath == null) lastError = "Tidak ada gambar PNG di dalam ZIP.";
        return fallbackPath;
    }

    private static void replace(File from, File to) throws IOException {
        if (to.exists() && !to.delete()) throw new IOException("tidak bisa menimpa " + to.getName());
        if (!from.renameTo(to)) throw new IOException("tidak bisa menyimpan " + to.getName());
    }

    /** Copies at most `limit` bytes. Returns false (and deletes the partial file) if the data is larger. */
    private static boolean copyLimited(InputStream in, File dest, long limit) throws IOException {
        OutputStream os = new FileOutputStream(dest);
        boolean ok = true;
        try {
            byte[] buffer = new byte[8192];
            long total = 0;
            int read;
            while ((read = in.read(buffer)) > 0) {
                total += read;
                if (total > limit) {
                    ok = false;
                    break;
                }
                os.write(buffer, 0, read);
            }
        } finally {
            os.close();
        }
        if (!ok) dest.delete();
        return ok;
    }

    private static int readUpTo(InputStream in, byte[] buf) throws IOException {
        int total = 0;
        while (total < buf.length) {
            int n = in.read(buf, total, buf.length - total);
            if (n < 0) break;
            total += n;
        }
        return total;
    }

    private static String shortMessage(Throwable t) {
        String m = t.getMessage();
        if (m == null || m.length() == 0) m = t.getClass().getSimpleName();
        return m.length() > 120 ? m.substring(0, 120) : m;
    }

    /** Fails with an IOException once more than `limit` bytes were read (zip bombs, endless streams). */
    private static final class LimitedInputStream extends FilterInputStream {
        private long left;

        LimitedInputStream(InputStream in, long limit) {
            super(in);
            left = limit;
        }

        @Override public int read() throws IOException {
            int b = super.read();
            if (b >= 0 && --left < 0) throw new IOException("file terlalu besar");
            return b;
        }

        @Override public int read(byte[] buf, int off, int len) throws IOException {
            int n = super.read(buf, off, len);
            if (n > 0) {
                left -= n;
                if (left < 0) throw new IOException("file terlalu besar");
            }
            return n;
        }
    }
}
