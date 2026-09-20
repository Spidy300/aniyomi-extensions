# NikaStream Aniyomi extension — setup

## What's in this folder
- `src/main/kotlin/.../NikaStream.kt` — the source implementation
- `src/main/AndroidManifest.xml` — link-handling for nikastream.blog/.sbs anime pages
- `build.gradle.kts` — module config

## How it works
- Browse / Search / Details / Episode count → AniList GraphQL (same as
  your site's `js/anime.js` / `js/search.js`)
- Video links → your own `anivexa-api.sudeepdon119.workers.dev` worker,
  `/watch/anikoto/{anilistId}/{sub|dub}/anikoto-{ep}` — same call your
  `js/episode.js` (`_astaAnikotoGetStreamData`) makes for the ASTA player.
- No scraping of nikastream.blog/.sbs HTML at all.

## Why you can't just build this single file standalone
Aniyomi/Mihon extensions aren't standalone Gradle projects — they're built
inside the **aniyomi-extensions monorepo**, which supplies:
- `AnimeHttpSource`, `SAnime`, `SEpisode`, `Video` etc. (the extensions-lib)
- shared Gradle plugin (`extName`/`extClass`/signing/APK packaging)
- the repo's `index.min.json` generation for self-hosting

### Steps
1. Fork/clone `https://github.com/aniyomiorg/aniyomi-extensions` (or a
   maintained fork if that org's repo has moved — check the current
   canonical repo linked from aniyomi.org first).
2. Copy this folder to `src/all/nikastream/` in that repo.
3. Add `nikastream` to the root `settings.gradle.kts` include list.
4. `./gradlew :src:all:nikastream:assembleDebug` to build the APK locally
   and sideload it, or push to your own fork's `repo` branch + CI to
   self-host (Settings → Browse → Anime extension repos → add your
   `index.min.json` URL, same pattern Secozzi's fork uses).
5. To test without CI: `adb install` the built APK directly.

## Things to double check / fill in
- `anivexa-api`'s `/watch/anikoto/...` endpoint occasionally 500s for
  multi-season titles and needs a MAL-ID retry (your `episode.js` handles
  this via `_aniZoneResolveMalId()`). I left that out for simplicity —
  add it back if you see failures on split-cour shows.
- Dub availability isn't guaranteed for every title/episode; if the dub
  request 404s, fall back to sub (worth adding a try/catch around
  `videoListRequest`/`videoListParse`).
- If you'd rather also expose your `animenosub`/`anineko` providers as
  quality options, mirror the same pattern used for `anikoto` in
  `videoListParse` — hit all three endpoints and merge the `streams[]`.

## Heads up
Since this pulls from your API and skips your ad-supported frontend
entirely, it also skips your monetization. Worth deciding if you want
this public or just for your own personal use before publishing it
anywhere.
