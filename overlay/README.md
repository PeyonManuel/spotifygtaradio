# GTA Radio Overlay (separate Android app)

A separate app from the main radio. It shows the same radio, plus a **wheel you can pull up over any app**:
swipe left on the thin handle near the right edge of the screen, slide to a station while still holding, lift your finger.
The wheel closes 1.5 s after your last touch (touch it again to keep it open). If Spotify isn't running, it opens Spotify first.

* The page lives in `overlay/index.html` (published at `/spotifygtaradio/overlay/` on GitHub Pages). The main page is untouched.
* The Android code is in `overlay/app/native/` and is built by the **Build Overlay APK** workflow (Actions → Run workflow), which publishes `GtaRadioOverlay.apk` to Releases.
* In the Spotify developer dashboard, add this redirect URI: `gtaoverlay://callback`.
* First run in the app: log in, tap **Overlay: off** in the bottom menu, allow "Display over other apps", tap it again.
* Tuning: `EDGE_GAP_DP`, `STRIP_W_DP`, `STRIP_H_FRACTION` at the top of `OverlayService.java`.
