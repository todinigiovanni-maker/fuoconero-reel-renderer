# Fuoconero Netflix — independent feasibility APK

Base: `fuoconero-tv-bridge-043-netflix-safe-test`, commit `76eeb2f30c865dafbebf7976712a57b6842f7ddd`.
Application ID: `com.fuoconero.netflixbridge.newsit.test`, version 0.2.0-test (2). This variant installs alongside the original 0.1 test package.
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
- Manual update and a persisted daily Android job requiring network, also rescheduled after reboot or package replacement. Actual timing is Android-controlled; 24 hours is a requested interval, not a wall-clock guarantee. Removing permission or receiving an empty source removes mirrored titles and obsolete images on refresh.
- No embedded keys. No Netflix login or subscription handled by this app.

## Additional authorized metadata

[TMDB Discover](https://developer.themoviedb.org/reference/discover-tv) can filter catalog availability by streaming provider and region. [Watch providers](https://developer.themoviedb.org/reference/tv-series-watch-providers) uses JustWatch data and does **not** return full direct playback links. A TMDB ID must never be treated as a Netflix ID. Using these APIs needs an authorized key, correct territory, TMDB and JustWatch attribution and applicable licensing ([TMDB FAQ](https://developer.themoviedb.org/docs/faq)). This test does not activate that optional catalog or transfer credentials from existing apps. A future metadata-only card would explicitly open Netflix, unless supplied with a separately verified Netflix link.

## Build and TV checks

Java 17, Android SDK 35, Gradle 8.9: `gradle :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug` inside netflix-test. The dedicated workflow builds only this project.

Install this distinct APK. Open Fuoconero Netflix TEST, choose Consenti lettura, accept the Android permission, update, and enable the channel in Projectivy > Modifica canali. If the OEM does not present the public permission dialog, report that limitation instead of granting privileged permissions. Verify Netflix-origin count, real artwork, title navigation, fallback and source revocation. `resolvable_links` only counts resolved Android intents, not proven playback. Build success and synthetic instrumentation tests alone do not prove live Netflix interoperability.

Instrumentation covers exact ownership, hidden/duplicate rows, URL/component rejection, stripped URI grants, artwork host restrictions, path traversal and 16:9 output. Tests do not read Netflix private data or start playback.


## 0.2.0-test — Novità Netflix Italia

Two independent channels owned by this test package: **Netflix · Continua a guardare** (existing shared Watch Next mirror) and **Novità Netflix Italia** (new public editorial schedule). The stable `tv-bridge-020` project remains untouched.

The news source is Netflix's own public [Novità su Netflix](https://about.netflix.com/it/new-to-watch) page, using the same unauthenticated, read-only `https://about.netflix.com/api/data/releases?language=it&country=IT` endpoint used by that page. This is a public website implementation detail, **not a documented or guaranteed third-party API or a license to redistribute a complete catalog**. No credentials, session cookies, private Netflix APIs, scraping of account history, TMDB keys, or access-control bypasses are used. No third-party service is provisioned. Public Netflix metadata/artwork are displayed with source attribution in this personal test application.

The app requests film collection 2694103 and series collection 2170013 separately. Every response entry must explicitly match territory IT and the requested collection; foreign/fallback territory or changed schema rejects the refresh rather than relabelling another catalog as Italian. Games and specials are excluded. At most eight pages per collection, 30 unique cards, dated within the last 30 days and already released. This is **Netflix's editorial release selection**, not every newly licensed film, every catalog change, profile-specific availability, or a complete list of all seasons/episodes.

The watch links use the original Netflix `videoID` and the same `/watch/{videoID}` URL pattern actually published by the source page. A title/season/episode may redirect to its parent series. HTTP resolution is smoke-checked in `validation/public-source.json`; it does not prove Android Netflix navigation or playback. On TV, an exported Netflix handler must resolve before launch; otherwise the app opens Netflix. No TMDB or other provider ID is treated as a Netflix ID.

Cards are rendered as 960×540 PNG (16:9). Source artwork may be portrait: it is fitted within the wide card without stretching, so a portrait source can have side margins. Only Netflix CDN artwork is accepted for news cards; missing artwork uses a labelled title card. The read-only artwork provider permits only these generated PNG filenames.

Successful catalog refreshes are atomically saved and cached for 24 hours; failed refreshes retain the last valid snapshot, visibly labelled as saved data. Entries older than the 30-day window expire even offline. Automatic failure retries are throttled for 15 minutes; manual refresh is explicit. Android controls daily job execution around standby, reboot and power management. Opening the app also checks freshness. Netflix shared history remains separate and is cleared on permission revocation/empty readable source as before.

Build also runs 12 pure JVM catalog tests and Android runtime tests under Robolectric, plus compiles the on-device instrumentation APK. Robolectric simulates Android; it is not a Sony or Netflix interoperability test. To repeat the read-only live source check: `python tools/check_public_source.py` (requires Pillow for image decoding). Its report explicitly marks Sony connection and playback as unverified.

After installing the CI APK, open the app and enable **both** channels in Projectivy > Modifica canali. The existing permission is needed only for Continua a guardare, not for public news. Signature comparison found that the old workflow never saved its debug key: its cache path did not exist. The old installed test certificate and the first 0.2 CI certificate differed. That first 0.2 artifact is superseded and must not be used to update the old package. This variant therefore uses a new application ID and a matching dynamic artwork authority, so it installs alongside the original 0.1 test without uninstalling or modifying it. The CI workflow explicitly creates/restores a test key at FUOCONERO_TEST_KEYSTORE before building and caches that exact file for subsequent updates of this new package. The artifact is named Fuoconero-Netflix-Novita-Italia-0.2.0-test. Once installed, enable the new app's two channels in Projectivy; the old test app remains separately available.
