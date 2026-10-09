package com.manuel.gtaoverlay;

import android.app.AppOpsManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Process;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;

/**
 * The radio wheel on top of every app.
 *
 * Two overlay windows:
 *  - a thin invisible strip near the right edge: swipe left on it and the wheel opens;
 *  - a full-screen, see-through window with a WebView showing the wheel page (?overlay=1). It sits there
 *    invisible and untouchable (so apps below work normally) until the strip is swiped.
 * While the finger is still down, its touches are forwarded to the wheel so you can slide to a station and lift to tune.
 * The page closes itself 1.5 s after the last touch (GtaOverlayHost.close()).
 */
public class OverlayService extends Service {
    public static volatile boolean running = false;

    private static final String PAGE_HOST = "peyonmanuel.github.io";
    private static final String PAGE_URL = "https://" + PAGE_HOST + "/spotifygtaradio/overlay/?overlay=1";
    private static final String CHANNEL = "gta_overlay";
    private static final int EDGE_GAP_DP = 26;      // distance of the swipe strip from the screen edge (keeps clear of the system "back" gesture)
    private static final int STRIP_W_DP = 20;
    private static final float STRIP_H_FRACTION = 0.32f;
    // Never exactly 0: Android only lets an app start Spotify's screen from the background while it has a *visible* overlay window.
    private static final float IDLE_ALPHA = 0.01f;
    private static final int SWIPE_DP = 36;         // how far you drag before the wheel opens

    private final Handler h = new Handler(Looper.getMainLooper());
    private WindowManager wm;
    private WebView web;
    private FrameLayout wheelHost, strip;
    private View pill;
    private boolean stripAttached = false;
    private WindowManager.LayoutParams wheelLp, stripLp;
    private boolean open = false;

    private float sx, sy;
    private boolean forwarding = false;
    private long downTime = 0;

    private final Runnable hideRun = new Runnable() {
        @Override public void run() {
            if (open || wheelHost == null) return;
            wheelLp.flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
            wheelLp.alpha = IDLE_ALPHA;
            try { wm.updateViewLayout(wheelHost, wheelLp); } catch (Exception ignored) {}
        }
    };
    private final Runnable watchdog = new Runnable() {
        @Override public void run() { closeWheel(); }
    };

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override
    public void onCreate() {
        super.onCreate();
        running = true;
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        startAsForeground();
        try {
            buildWheel();
            buildStrip();
        } catch (Exception e) {
            stopSelf();
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String a = intent == null ? null : intent.getAction();
        if ("STOP".equals(a)) {
            stopSelf();
        } else if ("OPEN".equals(a)) {          // from the notification button: open the wheel without a swipe
            if (wheelHost != null) openWheel(true);
        } else if ("APPLY".equals(a)) {         // options changed in the app
            applyOptions();
        }
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        running = false;
        h.removeCallbacksAndMessages(null);
        try { if (strip != null && stripAttached) wm.removeView(strip); } catch (Exception ignored) {}
        try { if (wheelHost != null) wm.removeView(wheelHost); } catch (Exception ignored) {}
        if (web != null) { web.destroy(); web = null; }
        super.onDestroy();
    }

    @Override
    public void onConfigurationChanged(Configuration c) {
        super.onConfigurationChanged(c);
        if (strip != null && stripAttached) {
            stripLp.height = (int) (getResources().getDisplayMetrics().heightPixels * STRIP_H_FRACTION);
            try { wm.updateViewLayout(strip, stripLp); } catch (Exception ignored) {}
        }
    }

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    @SuppressWarnings("deprecation")
    private int overlayType() {
        return Build.VERSION.SDK_INT >= 26 ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY : WindowManager.LayoutParams.TYPE_PHONE;
    }

    private void startAsForeground() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(new NotificationChannel(CHANNEL, "Radio wheel overlay", NotificationManager.IMPORTANCE_LOW));
            b = new Notification.Builder(this, CHANNEL);
        } else {
            b = new Notification.Builder(this);
        }
        int immutable = Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0;
        Intent launch = getPackageManager().getLaunchIntentForPackage(getPackageName());
        if (launch != null) b.setContentIntent(PendingIntent.getActivity(this, 0, launch, immutable | PendingIntent.FLAG_UPDATE_CURRENT));
        Intent open = new Intent(this, OverlayService.class).setAction("OPEN");
        b.addAction(android.R.drawable.ic_media_play, "Open wheel",
                PendingIntent.getService(this, 2, open, immutable | PendingIntent.FLAG_UPDATE_CURRENT));
        Intent stop = new Intent(this, OverlayService.class).setAction("STOP");
        b.addAction(android.R.drawable.ic_menu_close_clear_cancel, "Turn off",
                PendingIntent.getService(this, 1, stop, immutable | PendingIntent.FLAG_UPDATE_CURRENT));
        b.setSmallIcon(android.R.drawable.ic_media_play)
                .setContentTitle("GTA Spotify Radio")
                .setContentText("Swipe left near the right edge to open the wheel")
                .setOngoing(true);
        Notification n = b.build();
        if (Build.VERSION.SDK_INT >= 34) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        else startForeground(1, n);
    }

    private void buildWheel() {
        web = new WebView(this);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);                       // same storage as the app page: login + stations are shared
        s.setMediaPlaybackRequiresUserGesture(false);
        web.setBackgroundColor(Color.TRANSPARENT);
        web.setOverScrollMode(View.OVER_SCROLL_NEVER);
        web.setVerticalScrollBarEnabled(false);
        web.setHorizontalScrollBarEnabled(false);
        web.addJavascriptInterface(new Host(), "GtaOverlayHost");
        web.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                return !PAGE_HOST.equals(r.getUrl().getHost());
            }
        });
        web.setOnTouchListener((v, e) -> { bumpWatchdog(); return false; });
        web.loadUrl(PAGE_URL);

        wheelHost = new FrameLayout(this);
        wheelHost.addView(web, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        wheelLp = new WindowManager.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, overlayType(),
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                        | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                PixelFormat.TRANSLUCENT);
        wheelLp.alpha = IDLE_ALPHA;                          // practically invisible and untouchable until opened
        if (Build.VERSION.SDK_INT >= 30) wheelLp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        else if (Build.VERSION.SDK_INT >= 28) wheelLp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        wm.addView(wheelHost, wheelLp);
    }

    private void buildStrip() {
        strip = new FrameLayout(this);
        strip.setBackgroundColor(0x01000000);                // practically invisible, but it still receives touches
        pill = new View(this);                                // a faint hint of where to swipe (off by default, see options)
        GradientDrawable g = new GradientDrawable();
        g.setColor(0x44FFFFFF);
        g.setCornerRadius(dp(2));
        pill.setBackground(g);
        FrameLayout.LayoutParams pl = new FrameLayout.LayoutParams(dp(4), dp(72), Gravity.CENTER_VERTICAL | Gravity.RIGHT);
        pl.rightMargin = dp(2);
        strip.addView(pill, pl);
        strip.setOnTouchListener((v, e) -> onStripTouch(e));

        stripLp = new WindowManager.LayoutParams(
                dp(STRIP_W_DP), (int) (getResources().getDisplayMetrics().heightPixels * STRIP_H_FRACTION), overlayType(),
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                PixelFormat.TRANSLUCENT);
        stripLp.gravity = Gravity.RIGHT | Gravity.CENTER_VERTICAL;
        stripLp.x = dp(EDGE_GAP_DP);
        applyOptions();
    }

    /** Reads the options saved by the app: "handle" (the swipe strip at all) and "hint" (the faint line showing where it is). */
    private void applyOptions() {
        if (strip == null) return;
        pill.setVisibility(opt(this, "hint", false) ? View.VISIBLE : View.GONE);
        boolean want = opt(this, "handle", true);
        try {
            if (want && !stripAttached) { wm.addView(strip, stripLp); stripAttached = true; }
            else if (!want && stripAttached) { wm.removeView(strip); stripAttached = false; }
        } catch (Exception ignored) {}
    }

    public static boolean opt(Context c, String key, boolean def) {
        return c.getSharedPreferences("opts", Context.MODE_PRIVATE).getBoolean(key, def);
    }

    private boolean onStripTouch(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                sx = e.getRawX(); sy = e.getRawY(); forwarding = false;
                return true;
            case MotionEvent.ACTION_MOVE:
                if (!forwarding) {
                    float dx = sx - e.getRawX(), dy = Math.abs(e.getRawY() - sy);
                    if (dx > dp(SWIPE_DP) && dx > dy * 1.2f) {         // a leftward swipe
                        forwarding = true;
                        openWheel(false);
                        downTime = SystemClock.uptimeMillis();
                        forward(MotionEvent.ACTION_DOWN, e.getRawX(), e.getRawY());
                    }
                } else {
                    forward(MotionEvent.ACTION_MOVE, e.getRawX(), e.getRawY());
                }
                return true;
            case MotionEvent.ACTION_UP:
                if (forwarding) forward(MotionEvent.ACTION_UP, e.getRawX(), e.getRawY());
                forwarding = false;
                return true;
            case MotionEvent.ACTION_CANCEL:
                if (forwarding) forward(MotionEvent.ACTION_CANCEL, e.getRawX(), e.getRawY());
                forwarding = false;
                return true;
        }
        return true;
    }

    /** Sends the still-held finger to the wheel page as if it had touched it directly. */
    private void forward(int action, float rawX, float rawY) {
        if (web == null) return;
        int[] loc = new int[2];
        web.getLocationOnScreen(loc);
        MotionEvent.PointerProperties[] pp = { new MotionEvent.PointerProperties() };
        pp[0].id = 0;
        pp[0].toolType = MotionEvent.TOOL_TYPE_FINGER;
        MotionEvent.PointerCoords[] pc = { new MotionEvent.PointerCoords() };
        pc[0].x = rawX - loc[0];
        pc[0].y = rawY - loc[1];
        pc[0].pressure = 1f;
        pc[0].size = 1f;
        MotionEvent ev = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, 1, pp, pc,
                0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0);
        web.dispatchTouchEvent(ev);
        ev.recycle();
    }

    private void openWheel(boolean idle) {
        h.removeCallbacks(hideRun);
        if (!open) {
            open = true;
            wheelLp.flags &= ~WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
            wheelLp.alpha = 1f;
            try { wm.updateViewLayout(wheelHost, wheelLp); } catch (Exception ignored) {}
            eval("window.ovOpen&&ovOpen(" + idle + ")");
        }
        bumpWatchdog();
    }

    private void closeWheel() {
        if (!open) return;
        open = false;
        h.removeCallbacks(watchdog);
        eval("window.ovClose&&ovClose()");
        h.postDelayed(hideRun, 260);                          // let the fade-out finish, then make the window untouchable again
    }

    private void bumpWatchdog() {                             // safety net: never leave the wheel stuck on screen
        h.removeCallbacks(watchdog);
        if (open) h.postDelayed(watchdog, 10000);
    }

    private void eval(String js) {
        if (web != null) web.evaluateJavascript(js, null);
    }

    /**
     * Wakes Spotify so it shows up as a playback device.
     * front=false: a media-button press sent to Spotify, which starts it in the background (no screen change).
     * front=true: opens the Spotify app (allowed from the background because we may draw over other apps); the app you were in is
     * remembered (only if "usage access" is allowed) so returnToPrevious() can take you back.
     */
    private static String prevPkg = null;

    public static void openSpotify(Context c, boolean front) {
        if (!front) {
            try {
                for (int action : new int[]{KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP}) {
                    Intent i = new Intent(Intent.ACTION_MEDIA_BUTTON).setPackage("com.spotify.music");
                    i.putExtra(Intent.EXTRA_KEY_EVENT, new KeyEvent(action, KeyEvent.KEYCODE_MEDIA_PLAY));
                    c.sendBroadcast(i);
                }
            } catch (Exception ignored) {}
            return;
        }
        prevPkg = lastForegroundApp(c);
        try {
            Intent i = c.getPackageManager().getLaunchIntentForPackage("com.spotify.music");
            if (i != null) { i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION); c.startActivity(i); return; }
        } catch (Exception ignored) {}
        try {
            Intent i = new Intent(Intent.ACTION_VIEW, android.net.Uri.parse("spotify:"));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            c.startActivity(i);
        } catch (Exception ignored) {}
    }

    /** Takes you back to the app you were using before Spotify had to be shown (or to the home screen). */
    public static void returnToPrevious(Context c) {
        String p = prevPkg; prevPkg = null;
        if (p == null) return;
        try {
            Intent i = c.getPackageManager().getLaunchIntentForPackage(p);
            if (i == null) i = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION);
            c.startActivity(i);
        } catch (Exception ignored) {}
    }

    public static boolean hasUsageAccess(Context c) {
        try {
            AppOpsManager ops = (AppOpsManager) c.getSystemService(Context.APP_OPS_SERVICE);
            return ops.checkOpNoThrow("android:get_usage_stats", Process.myUid(), c.getPackageName()) == AppOpsManager.MODE_ALLOWED;
        } catch (Exception e) { return false; }
    }

    @SuppressWarnings("deprecation")
    private static String lastForegroundApp(Context c) {
        if (!hasUsageAccess(c)) return null;
        try {
            UsageStatsManager u = (UsageStatsManager) c.getSystemService(Context.USAGE_STATS_SERVICE);
            long now = System.currentTimeMillis();
            UsageEvents ev = u.queryEvents(now - 5 * 60 * 1000L, now);
            UsageEvents.Event e = new UsageEvents.Event();
            String last = null;
            while (ev.hasNextEvent()) {
                ev.getNextEvent(e);
                if (e.getEventType() != UsageEvents.Event.MOVE_TO_FOREGROUND) continue;
                String p = e.getPackageName();
                if (p == null || p.equals(c.getPackageName()) || p.equals("com.spotify.music") || p.equals("com.android.systemui")) continue;
                last = p;
            }
            return last;
        } catch (Exception ex) { return null; }
    }

    private class Host {
        @JavascriptInterface
        public void close() { h.post(OverlayService.this::closeWheel); }

        @JavascriptInterface
        public void openSpotify(String mode) { h.post(() -> OverlayService.openSpotify(OverlayService.this, "front".equals(mode))); }

        @JavascriptInterface
        public void returnToPrevious() { h.post(() -> OverlayService.returnToPrevious(OverlayService.this)); }
    }
}
