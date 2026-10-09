package com.manuel.gtaoverlay;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.ViewGroup;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import org.json.JSONObject;

/** The app screen: the radio page in a plain WebView, plus the buttons the page needs (Spotify login, overlay on/off). */
public class MainActivity extends Activity {
    private static final String PAGE_HOST = "peyonmanuel.github.io";
    private static final String PAGE_URL = "https://" + PAGE_HOST + "/spotifygtaradio/overlay/";

    private WebView web;
    private String pendingUrl = null;      // a gtaoverlay://callback link that arrived before the page could take it
    private boolean pageReady = false;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.parseColor("#0d0d12"));
        root.setFitsSystemWindows(true);

        web = new WebView(this);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        web.setBackgroundColor(Color.parseColor("#0d0d12"));
        web.addJavascriptInterface(new Native(), "GtaNative");
        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                if (PAGE_HOST.equals(r.getUrl().getHost())) return false;
                try { startActivity(new Intent(Intent.ACTION_VIEW, r.getUrl())); } catch (Exception ignored) {}
                return true;
            }
        });
        root.addView(web, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(root);

        takeLink(getIntent());
        web.loadUrl(PAGE_URL);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        takeLink(intent);
        deliverLink();
    }

    @Override
    public void onBackPressed() {
        if (web != null && web.canGoBack()) web.goBack();
        else moveTaskToBack(true);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (web != null) web.onResume();
    }

    @Override
    protected void onDestroy() {
        if (web != null) { web.destroy(); web = null; }
        super.onDestroy();
    }

    private void takeLink(Intent i) {
        Uri d = i == null ? null : i.getData();
        if (d != null && "gtaoverlay".equals(d.getScheme())) pendingUrl = d.toString();
    }

    private void deliverLink() {
        if (pendingUrl == null || web == null || !pageReady) return;
        String url = pendingUrl; pendingUrl = null;
        web.evaluateJavascript("window.onNativeUrl&&onNativeUrl(" + JSONObject.quote(url) + ")", null);
    }

    /** What the page can call (window.GtaNative). */
    private class Native {
        @JavascriptInterface
        public void pageReady() { runOnUiThread(() -> { pageReady = true; deliverLink(); }); }

        @JavascriptInterface
        public void openLogin(String url) {
            Uri u = Uri.parse(url);
            if (!"accounts.spotify.com".equals(u.getHost())) return;       // only the Spotify login page
            runOnUiThread(() -> startActivity(new Intent(Intent.ACTION_VIEW, u)));
        }

        @JavascriptInterface
        public String overlayStatus() {
            try {
                JSONObject o = new JSONObject();
                o.put("granted", Settings.canDrawOverlays(MainActivity.this));
                o.put("running", OverlayService.running);
                return o.toString();
            } catch (Exception e) { return "{}"; }
        }

        @JavascriptInterface
        public void overlayRequestPermission() {
            runOnUiThread(() -> startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName()))));
        }

        @JavascriptInterface
        public void overlayStart() {
            runOnUiThread(() -> {
                if (!Settings.canDrawOverlays(MainActivity.this)) return;
                Intent i = new Intent(MainActivity.this, OverlayService.class);
                if (Build.VERSION.SDK_INT >= 26) startForegroundService(i); else startService(i);
            });
        }

        @JavascriptInterface
        public void openSpotify() { runOnUiThread(() -> OverlayService.openSpotify(MainActivity.this)); }

        @JavascriptInterface
        public void overlayStop() {
            runOnUiThread(() -> stopService(new Intent(MainActivity.this, OverlayService.class)));
        }
    }
}
