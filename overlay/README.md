# GTA Radio Overlay (separate Android app)

A separate app from the main radio. It shows the same radio, plus a **wheel you can pull up over any app**:
swipe right on the thin handle near the left edge of the screen, slide to a station while still holding, lift your finger.
The wheel closes 1.5 s after your last touch (touch it again to keep it open). If Spotify isn't running, it opens Spotify first.

* The page lives in `overlay/index.html` (published at `/spotifygtaradio/overlay/` on GitHub Pages). The main page is untouched.
* The Android code is in `overlay/app/native/` and is built by the **Build Overlay APK** workflow (Actions → Run workflow), which publishes `GtaRadioOverlay.apk` to Releases.
* In the Spotify developer dashboard, add this redirect URI: `gtaoverlay://callback`.
* First run in the app: log in, tap **Overlay: off** in the bottom menu, allow "Display over other apps", tap it again.
* Tuning: `EDGE_GAP_DP`, `STRIP_W_DP`, `STRIP_H_FRACTION` at the top of `OverlayService.java`.

## Options (bottom menu, only in the app)
* **Swipe handle: on/off** - turn the edge handle off completely (then use the "Open wheel" button in the notification).
* **Handle hint: on/off** - the faint line showing where the handle is (off by default).
* **Return to app: on/off** - after Spotify had to be shown to wake it, go back to the app you were in. Needs "Usage access" (Android settings).

## Waking Spotify
No device found: the radio keeps "tuning", sends Spotify a background media-button press, refreshes the device list until the phone shows up,
switches playback to it and plays. If Spotify still hasn't appeared after ~5 s it opens the Spotify app for a moment and then returns you to your app.

## Known limit
Android can't let a window both catch swipes and pass plain taps through, so the handle strip (20dp wide, 32% of the screen height, set 26dp in from the edge)
does take taps on that sliver. Make it smaller, move it, or turn it off with the options above.

## Which device plays
The radio only plays on this phone (a Spotify device of type Smartphone/Tablet) or on a device you pick yourself in the device list.
It never falls back to Chrome's web player or another computer: if the phone isn't listed, it wakes Spotify and switches to the phone.
Choosing the station you're already on does nothing (no switch sound).
