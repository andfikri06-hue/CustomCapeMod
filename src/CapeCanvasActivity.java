package org.levimc.launcher.customcape;

import android.app.Activity;
import android.os.Bundle;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;

/**
 * Hosts the pixel-art cape editor (assets/cape_canvas.html) in a WebView.
 * Opened via CapeCanvasBridge::openCapeCanvasEditor() in the native mod.
 *
 * NOTE: this needs to be compiled into a .dex your LeviLaunchroid build
 * actually loads. If your other native mods don't currently ship any
 * Java classes of their own, check with LeviLaunchroid's docs/repo for
 * whether "native mod + companion Java class" is a supported pattern —
 * if not, the fallback is to keep this Activity registered directly in
 * LeviLauncher's own manifest/classpath instead of the mod's.
 */
public class CapeCanvasActivity extends Activity {

    private WebView webView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        String htmlPath = getIntent().getStringExtra("html_path");

        webView = new WebView(this);
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setAllowFileAccess(true);
        settings.setDomStorageEnabled(true);

        webView.addJavascriptInterface(new JsBridge(), "AndroidCape");
        setContentView(webView);

        if (htmlPath != null) {
            webView.loadUrl("file://" + htmlPath);
        }
    }

    @Override
    public void onBackPressed() {
        // Let the web app confirm unsaved changes before closing, if it wants to.
        webView.evaluateJavascript("window.onBackRequested && window.onBackRequested()", null);
    }

    /** Exposed to the HTML/JS canvas app as `AndroidCape.*`. */
    private class JsBridge {

        @JavascriptInterface
        public void saveCape(String base64Rgba, int width, int height, String name) {
            nativeOnCapeSaved(base64Rgba, width, height, name);
            runOnUiThread(CapeCanvasActivity.this::finish);
        }

        @JavascriptInterface
        public void cancel() {
            runOnUiThread(CapeCanvasActivity.this::finish);
        }
    }

    /** Implemented in CapeCanvasBridge.cpp. */
    private native void nativeOnCapeSaved(String base64Rgba, int width, int height, String name);
}
