# AirCast

Android app that plays audio from your phone on an **AirPlay speaker** (HomePod mini, HomePod, Apple TV, AirPlay 2 speakers).

Android has no built-in AirPlay sender, so the app embeds [pyatv](https://pyatv.dev) (via [Chaquopy](https://chaquo.com/chaquopy/)) to speak the AirPlay/RAOP protocol, and uses Android's own decoder (`MediaCodec`) to turn any audio file into the PCM stream AirPlay needs.

> This is a hobby-grade app. Streaming goes over your **local Wi-Fi**; it cannot run on Vercel/Render because those servers cannot reach a speaker inside your home network.

## Get the APK

1. Push this repo to GitHub (the Actions workflow in `.github/workflows/build-apk.yml` builds it).
2. Open the repo → **Actions** → latest "Build APK" run → download the `aircast-debug-apk` artifact, **or**
   open the repo → **Releases** → `AirCast (latest build)` and download `AirCast.apk` straight on your phone.
3. Install it (allow "Install unknown apps" for your browser/files app when asked).

## Use it

1. HomePod setup (one-time): iPhone/iPad **Home app** → long-press the HomePod → Settings → **Allow Speaker & TV Access** → **Anyone on the Same Network** (otherwise Android can't pair with it).
2. Connect the phone to the **same Wi-Fi** as the HomePod. Turn off any VPN. If the Wi-Fi router has "client isolation / AP isolation" on, turn it off.
3. Open AirCast → **Scan for speakers** → tap your HomePod. If nothing shows up, type the HomePod's IP address (router's device list) in the box and scan again.
4. **Choose audio file** (mp3, m4a/aac, flac, ogg, wav…) → **Play**. Playback continues with the screen off (a notification with a Stop button is shown).

## Limitations

- Plays **local audio files** (not Spotify/Apple Music/YouTube – those apps don't expose their audio).
- The whole file is decoded first, so long tracks take a few seconds to start.
- No Siri / HomePod controls while streaming this way; volume is controlled on the HomePod itself.
- Debug build only (signed with the standard debug key).

## Troubleshooting

- In the app, **Show debug log** displays the pyatv log; include it when reporting a problem.
- *No speakers found*: same Wi-Fi? VPN off? Try entering the IP manually.
- *Connection / pairing errors*: re-check the HomePod "Anyone on the Same Network" setting. If your HomePod requires a password or home-members-only access, this app can't connect.
- Android 16+ may ask for a "nearby devices / local network" permission – allow it.

## Project layout

```
app/src/main/kotlin/dev/aircast/   Kotlin UI, audio decoder (MediaCodec → WAV), foreground playback service
app/src/main/python/bridge.py      pyatv scan/stream logic (runs inside Chaquopy)
app/src/main/python/miniaudio.py   tiny WAV-only replacement for pyatv's native "miniaudio" dependency
.github/workflows/build-apk.yml    cloud build → APK
```

Build locally (Android Studio or `gradle :app:assembleDebug`, JDK 17 + Android SDK 34).
