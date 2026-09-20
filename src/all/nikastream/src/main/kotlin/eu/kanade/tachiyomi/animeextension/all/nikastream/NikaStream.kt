package eu.kanade.tachiyomi.animeextension.all.nikastream

import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import okhttp3.Headers
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import android.app.Application
import android.content.SharedPreferences

/**
 * NikaStream (nikastream.sbs / nikastream.blog) extension for Aniyomi.
 *
 * Metadata (browse / search / details / episode count) is pulled straight
 * from AniList's public GraphQL API, exactly like the NikaStream frontend
 * does in js/anime.js and js/search.js.
 *
 * Video links are pulled from NikaStream's own aggregator worker,
 * anivexa-api.sudeepdon119.workers.dev, the same endpoint the "ASTA"
 * player on the site itself calls (see js/episode.js,
 * _astaAnikotoGetStreamData()). No HTML scraping of nikastream.* is
 * needed or done here.
 */
class NikaStream : ConfigurableAnimeSource, AnimeHttpSource() {

    override val name = "NikaStream"
    override val baseUrl = "https://nikastream.sbs"
    override val lang = "all"
    override val supportsLatest = true

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    override val client: OkHttpClient = network.client

    private val anilistUrl = "https://graphql.anilist.co"
    private val anivexaBase = "https://anivexa-api.sudeepdon119.workers.dev"

    // ───────────────────────── Popular / Latest ─────────────────────────

    override fun popularAnimeRequest(page: Int): Request = anilistPageRequest(
        page = page,
        sort = "POPULARITY_DESC",
    )

    override fun latestUpdatesRequest(page: Int): Request = anilistPageRequest(
        page = page,
        sort = "TRENDING_DESC",
    )

    override fun popularAnimeParse(response: Response) = parseAnilistPage(response)
    override fun latestUpdatesParse(response: Response) = parseAnilistPage(response)

    private fun anilistPageRequest(page: Int, sort: String): Request {
        val query = """
            query (${'$'}page: Int, ${'$'}sort: [MediaSort]) {
              Page(page: ${'$'}page, perPage: 30) {
                pageInfo { hasNextPage }
                media(type: ANIME, sort: ${'$'}sort) {
                  id
                  title { romaji english }
                  coverImage { extraLarge large }
                }
              }
            }
        """.trimIndent()
        val variables = JSONObject().apply {
            put("page", page)
            put("sort", JSONArray().put(sort))
        }
        return graphqlRequest(query, variables)
    }

    private fun parseAnilistPage(response: Response): eu.kanade.tachiyomi.animesource.model.AnimesPage {
        val json = JSONObject(response.body.string())
        val page = json.getJSONObject("data").getJSONObject("Page")
        val media = page.getJSONArray("media")
        val list = (0 until media.length()).map { i -> media.getJSONObject(i).toSAnime() }
        val hasNext = page.getJSONObject("pageInfo").getBoolean("hasNextPage")
        return eu.kanade.tachiyomi.animesource.model.AnimesPage(list, hasNext)
    }

    // ─────────────────────────── Search ───────────────────────────

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val gql = """
            query (${'$'}page: Int, ${'$'}search: String) {
              Page(page: ${'$'}page, perPage: 30) {
                pageInfo { hasNextPage }
                media(type: ANIME, search: ${'$'}search) {
                  id
                  title { romaji english }
                  coverImage { extraLarge large }
                }
              }
            }
        """.trimIndent()
        val variables = JSONObject().apply {
            put("page", page)
            put("search", query)
        }
        return graphqlRequest(gql, variables)
    }

    override fun searchAnimeParse(response: Response) = parseAnilistPage(response)

    // ─────────────────────────── Details ───────────────────────────

    override fun animeDetailsRequest(anime: SAnime): Request {
        val id = anime.url.trim('/')
        val gql = """
            query (${'$'}id: Int) {
              Media(id: ${'$'}id, type: ANIME) {
                id
                title { romaji english }
                description(asHtml: false)
                genres
                status
                episodes
                seasonYear
                averageScore
                coverImage { extraLarge large }
                nextAiringEpisode { episode }
              }
            }
        """.trimIndent()
        val variables = JSONObject().put("id", id.toInt())
        return graphqlRequest(gql, variables)
    }

    override fun animeDetailsParse(response: Response): SAnime {
        val media = JSONObject(response.body.string()).getJSONObject("data").getJSONObject("Media")
        return media.toSAnime(full = true)
    }

    private fun JSONObject.toSAnime(full: Boolean = false): SAnime {
        val id = getInt("id")
        val titleObj = getJSONObject("title")
        val title = titleObj.optString("english").ifBlank { titleObj.optString("romaji") }
        val cover = optJSONObject("coverImage")
        return SAnime.create().apply {
            url = "/$id"
            this.title = title
            thumbnail_url = cover?.optString("extraLarge") ?: cover?.optString("large")
            if (full) {
                description = optString("description", "").replace(Regex("<[^>]*>"), "")
                genre = optJSONArray("genres")?.let { arr -> (0 until arr.length()).joinToString(", ") { arr.getString(it) } }
                status = when (optString("status")) {
                    "RELEASING" -> SAnime.ONGOING
                    "FINISHED" -> SAnime.COMPLETED
                    else -> SAnime.UNKNOWN
                }
            }
        }
    }

    // ─────────────────────────── Episodes ───────────────────────────
    // NikaStream doesn't store per-episode titles; it just counts up from
    // AniList's `episodes` (or `nextAiringEpisode.episode - 1` for airing
    // shows) exactly like js/anime.js does. We do the same here.

    override fun episodeListRequest(anime: SAnime): Request = animeDetailsRequest(anime)

    override fun episodeListParse(response: Response): List<SEpisode> {
        val media = JSONObject(response.body.string()).getJSONObject("data").getJSONObject("Media")
        val animeId = media.getInt("id")
        val total = when {
            !media.isNull("episodes") && media.optInt("episodes") > 0 -> media.optInt("episodes")
            !media.isNull("nextAiringEpisode") -> media.getJSONObject("nextAiringEpisode").getInt("episode") - 1
            else -> 1
        }.coerceAtLeast(1)

        return (total downTo 1).map { ep ->
            SEpisode.create().apply {
                url = "/$animeId/$ep"
                name = "Episode $ep"
                episode_number = ep.toFloat()
            }
        }
    }

    // ─────────────────────────── Video links ───────────────────────────
    // Mirrors js/episode.js -> _astaAnikotoGetStreamData(): hits the
    // anikoto provider on anivexa-api for both sub and dub, and returns
    // whichever HLS stream(s) come back, preferring the one the API
    // itself flags isActive.

    override fun videoListRequest(episode: SEpisode): Request {
        val (animeId, ep) = episode.url.trim('/').split("/")
        val audio = preferences.getString(PREF_AUDIO_KEY, PREF_AUDIO_DEFAULT) ?: PREF_AUDIO_DEFAULT
        val url = "$anivexaBase/watch/anikoto/$animeId/$audio/anikoto-$ep"
        return GET(url, headers)
    }

    override fun videoListParse(response: Response): List<Video> {
        val data = JSONObject(response.body.string())
        val streams = data.optJSONArray("streams") ?: JSONArray()
        val referer = data.optJSONObject("headers")?.optString("Referer")?.ifBlank { null }
            ?: "https://megaplay.buzz/"

        val videos = mutableListOf<Video>()
        for (i in 0 until streams.length()) {
            val s = streams.getJSONObject(i)
            if (s.optString("type") != "hls" || s.optString("url").isBlank()) continue
            val quality = s.optString("server").ifBlank { if (s.optBoolean("isActive")) "HD (auto)" else "Server ${i + 1}" }
            val vidHeaders = Headers.Builder()
                .add("Referer", s.optString("referer").ifBlank { referer })
                .build()
            videos.add(Video(s.getString("url"), quality, s.getString("url"), headers = vidHeaders))
        }
        // Put the isActive stream first, same priority the site's player uses.
        return videos.sortedByDescending { it.quality.contains("auto", ignoreCase = true) }
    }

    // ─────────────────────────── Helpers ───────────────────────────

    private fun graphqlRequest(query: String, variables: JSONObject): Request {
        val body = JSONObject().apply {
            put("query", query)
            put("variables", variables)
        }.toString().toRequestBody("application/json".toMediaType())
        return Request.Builder()
            .url(anilistUrl)
            .post(body)
            .headers(headers)
            .build()
    }

    // ─────────────────────────── Preferences ───────────────────────────

    companion object {
        private const val PREF_AUDIO_KEY = "preferred_audio"
        private const val PREF_AUDIO_DEFAULT = "sub"
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        ListPreference(screen.context).apply {
            key = PREF_AUDIO_KEY
            title = "Preferred audio"
            entries = arrayOf("Sub", "Dub")
            entryValues = arrayOf("sub", "dub")
            setDefaultValue(PREF_AUDIO_DEFAULT)
            summary = "%s"
        }.also(screen::addPreference)
    }
}
