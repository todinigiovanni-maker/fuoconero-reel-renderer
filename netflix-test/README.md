# Fuoconero Netflix — independent feasibility APK

Base: `fuoconero-tv-bridge-043-netflix-safe-test`, commit `76eeb2f30c865dafbebf7976712a57b6842f7ddd`.
Application ID: `com.fuoconero.netflixbridge.test`, version 0.1.0-test (1).
This standalone Gradle project does not build or update tv-bridge-020. No stable app, credentials, playlist or database is copied or modified.

## Feasibility established before implementation

Android's [TvProvider manifest](https://android.googlesource.com/platform/packages/providers/TvProvider/+/refs/heads/main/AndroidManifest.xml) defines READ_TV_LISTINGS as dangerous (user-grantable), and ACCESS_ALL_EPG_DATA as signature/privileged. The [provider implementation](https://android.googlesource.com/platform/packages/providers/TvProvider/+/refs/heads/main/src/com/android/providers/tv/TvProvider.java) restricts ordinary queries to owned rows, or searchable rows with READ_TV_LISTINGS. Watch Next is not a guarantee of readable Netflix history: Netflix must publish it, the provider must expose it, and the user must grant permission. Empty results are inconclusive about private Netflix history.

Read-only package inspection on the Sony Bravia confirmed READ_TV_LISTINGS is dangerous and Netflix Android TV (`com.netflix.ninja`) declares VIEW handlers for HTTPS title/watch URLs and nflx://www.netflix.com. Resolution does not prove navigation to a title or automatic playback.

## Implemented

- Requests only the public listings permission through Android's runtime permission UI. Does not request privileged EPG access, root, accessibility, screen scraping, Netflix credentials, private content providers or session tokens.
- Queries the shared Watch Next contract read-only, with provider-side access restrictions intact. Filters exact package ownership (`com.netflix.ninja`, `com.netflix.mediaclient`), browsable rows, unique IDs, newest engagement first, at most 20 entries. No title-based guesses of Netflix origin.
- Publishes only its own dedicated channel and own preview rows. No changes to original Watch Next rows or other apps' channels.
- Real shared poster where readable, fitted without distortion into a 960x540 card. HTTPS artwork only from known Netflix CDN domains, no redirects; permission-denied/missing images produce an explicitly labelled text card. Progress is drawn only when the source supplies valid position and duration.
- Only an original shared Netflix VIEW intent is eligible. Rejects foreign package/component, selector, insecure or foreign URL; strips URI grants and resolves an exported Netflix activity before launch. Reloads the source on click, rather than trusting an external caller's supplied intent. No invented Netflix title IDs.
- Missing/unresolvable link opens the Netflix app; permanent Apri Netflix card remains available. If Netflix accepts a link but opens its home, Android cannot reliably detect that. The bridge does not pretend otherwise; Back exposes the fallback button.
- Manual update and hourly Android job, also rescheduled after reboot. Actual timing is Android-controlled, not an hourly guarantee. Removing permission or receiving an empty source removes mirrored titles and obsolete images on refresh.
- No embedded keys. No Netflix login or subscription handled by this app.

## Additional authorized metadata

[TMDB Discover](https://developer.themoviedb.org/reference/discover-tv) can filter catalog availability by streaming provider and region. [Watch providers](https://developer.themoviedb.org/reference/tv-series-watch-providers) uses JustWatch data and does **not** return full direct playback links. A TMDB ID must never be treated as a Netflix ID. Using these APIs needs an authorized key, correct territory, TMDB and JustWatch attribution and applicable licensing ([TMDB FAQ](https://developer.themoviedb.org/docs/faq)). This test does not activate that optional catalog or transfer credentials from existing apps. A future metadata-only card would explicitly open Netflix, unless supplied with a separately verified Netflix link.

## Build and TV checks

Java 17, Android SDK 35, Gradle 8.9: `gradle :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug` inside netflix-test. The dedicated workflow builds only this project.

Install this distinct APK. Open Fuoconero Netflix TEST, choose Consenti lettura, accept the Android permission, update, and enable the channel in Projectivy > Modifica canali. If the OEM does not present the public permission dialog, report that limitation instead of granting privileged permissions. Verify Netflix-origin count, real artwork, title navigation, fallback and source revocation. `resolvable_links` only counts resolved Android intents, not proven playback. Build success and synthetic instrumentation tests alone do not prove live Netflix interoperability.

Instrumentation covers exact ownership, hidden/duplicate rows, URL/component rejection, stripped URI grants, artwork host restrictions, path traversal and 16:9 output. Tests do not read Netflix private data or start playback.
