# Fuoconero TV Bridge • MVP 0.1.0

Android TV utility to validate Android TvProvider preview channels in Projectivy Launcher. **This build is a technical prototype, not a Trakt integration yet.**

## What it DOES
- Publishes one Android TV preview channel named "Fuoconero • Continua a guardare [TEST]".
- Adds two clearly labeled sample cards to that channel.
- Clicking a sample card launches Veezie HOME using an Android intent for `veezie://test`; it does NOT open a chosen movie or episode.
- Allows removing the TV channel from the app.
- Does not change Projectivy launcher preferences, Veezie data, IPTV playlists, or Trakt credentials.

## What it DOES NOT do
- No Trakt sign-in, history sync, poster download, or automatic scheduling yet.
- No deep-link into a particular film; Veezie's custom scheme is not yet known to support that.

## Build
In Android Studio open `android-tv-bridge/`. Use Gradle sync then Build > Build APK(s).
Or use the repository's CI workflow `Fuoconero TV Bridge debug APK` on branch `fuoconero-tv-bridge-mvp`.

## Install on Sony Bravia (using already configured ADB)
```powershell
.\adb.exe install -r .\app-debug.apk
```
Open the Fuoconero TV Bridge app, choose "Crea / aggiorna la riga di prova", then in Projectivy choose Settings > Edit channels and enable its Fuoconero channel. Test selecting either card.

## Uninstall/remove
Use the app's "Elimina la nostra riga di prova" button before uninstalling. Disable ADB debugging when finished.

## Technical details
Android min SDK 26; uses platform `android.media.tv.TvContract` and Android Studio AGP.
No third-party SDKs and no secrets. All network-free.
