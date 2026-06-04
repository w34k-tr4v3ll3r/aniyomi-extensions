package eu.kanade.tachiyomi.animeextension.en.watchanimeworld

import android.app.Application
import android.content.SharedPreferences
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.text.SimpleDateFormat
import java.util.Locale

class WatchAnimeWorld : AnimeHttpSource(), ConfigurableAnimeSource {

    override val name = "WatchAnimeWorld"
    override val baseUrl = "https://watchanimeworld.in"
    override val lang = "en"
    override val supportsLatest = true

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    override val client: OkHttpClient = network.cloudflareClient

    override fun popularAnimeRequest(page: Int): Request {
        return GET("$baseUrl/trending/page/$page/", headers)
    }

    override fun popularAnimeParse(response: Response): AnimesPage {
        val document = response.asJsoup()
        val animes = document.select("div.film_list-wrap article.item").map { element ->
            popularAnimeFromElement(element)
        }
        val hasNextPage = document.selectFirst("div.pagination a.next") != null
        return AnimesPage(animes, hasNextPage)
    }

    private fun popularAnimeFromElement(element: Element): SAnime {
        return SAnime.create().apply {
            setUrlWithoutDomain(element.selectFirst("a")!!.attr("href"))
            title = element.selectFirst("h3, h2, .film-name")?.text() ?: ""
            thumbnail_url = element.selectFirst("img")?.let {
                it.attr("data-src").ifEmpty { it.attr("src") }
            }
        }
    }

    override fun latestUpdatesRequest(page: Int): Request {
        return GET("$baseUrl/page/$page/", headers)
    }

    override fun latestUpdatesParse(response: Response): AnimesPage {
        val document = response.asJsoup()
        val animes = document.select("article.item, div.item, div.film_list-wrap article").map { element ->
            latestAnimeFromElement(element)
        }
        val hasNextPage = document.selectFirst("a.next.page-numbers, div.pagination a.next") != null
        return AnimesPage(animes, hasNextPage)
    }

    private fun latestAnimeFromElement(element: Element): SAnime {
        return SAnime.create().apply {
            setUrlWithoutDomain(element.selectFirst("a")!!.attr("href"))
            title = element.selectFirst("h3, h2, .film-name, .entry-title")?.text() ?: ""
            thumbnail_url = element.selectFirst("img")?.let {
                it.attr("data-src").ifEmpty { it.attr("src") }
            }
        }
    }

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val filterList = if (filters.isEmpty()) getFilterList() else filters
        val genreFilter = filterList.find { it is GenreFilter } as? GenreFilter
        val languageFilter = filterList.find { it is LanguageFilter } as? LanguageFilter
        val typeFilter = filterList.find { it is TypeFilter } as? TypeFilter

        return when {
            query.isNotBlank() -> {
                val url = "$baseUrl/page/$page/".toHttpUrl().newBuilder()
                    .addQueryParameter("s", query)
                    .build()
                GET(url.toString(), headers)
            }
            genreFilter?.state != 0 -> GET("$baseUrl/genre/${genreFilter!!.toUriPart()}/page/$page/", headers)
            languageFilter?.state != 0 -> GET("$baseUrl/language/${languageFilter!!.toUriPart()}/page/$page/", headers)
            typeFilter?.state != 0 -> GET("$baseUrl/type/${typeFilter!!.toUriPart()}/page/$page/", headers)
            else -> GET("$baseUrl/page/$page/", headers)
        }
    }

    override fun searchAnimeParse(response: Response): AnimesPage {
        val document = response.asJsoup()
        val animes = document.select("article.item, div.item, div.film_list-wrap article, .search-result article").map { element ->
            SAnime.create().apply {
                setUrlWithoutDomain(element.selectFirst("a")!!.attr("href"))
                title = element.selectFirst("h3, h2, .film-name, .entry-title")?.text() ?: ""
                thumbnail_url = element.selectFirst("img")?.let {
                    it.attr("data-src").ifEmpty { it.attr("src") }
                }
            }
        }
        val hasNextPage = document.selectFirst("a.next.page-numbers, div.pagination a.next") != null
        return AnimesPage(animes, hasNextPage)
    }

    override fun animeDetailsParse(response: Response): SAnime {
        val document = response.asJsoup()
        return SAnime.create().apply {
            title = document.selectFirst("h1.entry-title, h1.film-name, .sheader h1")?.text() ?: ""
            thumbnail_url = document.selectFirst(".poster img, .film-poster img, img.attachment-post-thumbnail")?.let {
                it.attr("data-src").ifEmpty { it.attr("src") }
            }
            description = document.selectFirst(".entry-content p, .film-description, .description, .synopsis")?.text()
            genre = document.select(".genres a, .genre a, a[rel~=tag]").joinToString(", ") { it.text() }
            status = when (document.selectFirst(".status, .film-status")?.text()?.lowercase()) {
                "ongoing", "airing" -> SAnime.ONGOING
                "completed", "finished" -> SAnime.COMPLETED
                else -> SAnime.UNKNOWN
            }
            author = document.selectFirst(".studio a, a[href*='studio']")?.text()
        }
    }

    override fun episodeListParse(response: Response): List<SEpisode> {
        val document = response.asJsoup()
        val episodes = mutableListOf<SEpisode>()
        val episodeElements = document.select(
            "a[href*='episode'], .episodes a, ul.episodios li a, .ep-item a, div.episodelist a, .episode-list a"
        )
        if (episodeElements.isNotEmpty()) {
            episodeElements.forEachIndexed { index, element ->
                val episode = SEpisode.create().apply {
                    setUrlWithoutDomain(element.attr("href"))
                    name = element.text().ifBlank { "Episode ${index + 1}" }
                    episode_number = extractEpisodeNumber(element.text()) ?: (index + 1).toFloat()
                    date_upload = parseDate(element.selectFirst("span.date, .date")?.text() ?: "")
                }
                episodes.add(episode)
            }
        } else {
            episodes.add(SEpisode.create().apply {
                setUrlWithoutDomain(response.request.url.toString().substringAfter(baseUrl))
                name = "Episode 1"
                episode_number = 1f
            })
        }
        return episodes.reversed()
    }

    private fun extractEpisodeNumber(text: String): Float? {
        val match = Regex("""[Ee]p(?:isode)?\s*(\d+(?:\.\d+)?)""").find(text)
            ?: Regex("""(\d+(?:\.\d+)?)""").find(text)
        return match?.groupValues?.get(1)?.toFloatOrNull()
    }

    private fun parseDate(dateStr: String): Long {
        return runCatching {
            SimpleDateFormat("MMMM d, yyyy", Locale.ENGLISH).parse(dateStr)?.time ?: 0L
        }.getOrDefault(0L)
    }

    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        val videos = mutableListOf<Video>()
        document.select("video source, video[src]").forEach { element ->
            val url = element.attr("src").ifEmpty { element.attr("data-src") }
            if (url.isNotBlank()) videos.add(Video(url, "Direct (${getQualityLabel(url)})", url))
        }
        document.select("iframe[src], iframe[data-src]").forEach { iframe ->
            val src = iframe.attr("src").ifEmpty { iframe.attr("data-src") }
            if (src.isNotBlank()) runCatching { videos.addAll(extractFromEmbed(src)) }
        }
        val scriptContent = document.select("script").joinToString("\n") { it.html() }
        Regex("""file\s*:\s*['"]([^'"]+\.(?:mp4|m3u8)[^'"]*)['"]""").findAll(scriptContent)
            .forEach { match -> videos.add(Video(match.groupValues[1], "JS Source (${getQualityLabel(match.groupValues[1])})", match.groupValues[1])) }
        Regex("""sources\s*:\s*\[\s*\{[^}]*file\s*:\s*['"]([^'"]+)['"]""").findAll(scriptContent)
            .forEach { match ->
                val url = match.groupValues[1]
                if (url.contains(".mp4") || url.contains(".m3u8")) videos.add(Video(url, "JWPlayer (${getQualityLabel(url)})", url))
            }
        return videos.ifEmpty { listOf(Video("", "No sources found", "")) }
    }

    private fun extractFromEmbed(embedUrl: String): List<Video> {
        val videos = mutableListOf<Video>()
        return runCatching {
            val embedDoc = client.newCall(GET(embedUrl, headers)).execute().asJsoup()
            embedDoc.select("video source, video[src]").forEach { element ->
                val url = element.attr("src").ifEmpty { element.attr("data-src") }
                if (url.isNotBlank()) videos.add(Video(url, "Embed (${getQualityLabel(url)})", url))
            }
            val scriptContent = embedDoc.select("script").joinToString("\n") { it.html() }
            Regex("""file\s*:\s*['"]([^'"]+\.(?:mp4|m3u8)[^'"]*)['""]""")
                .findAll(scriptContent)
                .forEach { match -> videos.add(Video(match.groupValues[1], "Embed JS (${getQualityLabel(match.groupValues[1])})", match.groupValues[1])) }
            videos
        }.getOrDefault(videos)
    }

    private fun getQualityLabel(url: String): String {
        return when {
            url.contains("1080") -> "1080p"
            url.contains("720") -> "720p"
            url.contains("480") -> "480p"
            url.contains("360") -> "360p"
            url.contains(".m3u8") -> "HLS"
            else -> "Unknown"
        }
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val videoQualityPref = ListPreference(screen.context).apply {
            key = PREF_QUALITY_KEY
            title = "Preferred quality"
            entries = arrayOf("1080p", "720p", "480p", "360p", "HLS", "Unknown")
            entryValues = arrayOf("1080p", "720p", "480p", "360p", "HLS", "Unknown")
            setDefaultValue("720p")
            summary = "%s"
        }
        screen.addPreference(videoQualityPref)
    }

    override fun getFilterList(): AnimeFilterList = AnimeFilterList(
        AnimeFilter.Header("NOTE: Filters are ignored when using text search"),
        GenreFilter(),
        LanguageFilter(),
        TypeFilter(),
    )

    private class GenreFilter : UriPartFilter(
        "Genre",
        arrayOf(
            Pair("<select>", ""), Pair("Action", "action"), Pair("Adventure", "adventure"),
            Pair("Comedy", "comedy"), Pair("Drama", "drama"), Pair("Ecchi", "ecchi"),
            Pair("Fantasy", "fantasy"), Pair("Horror", "horror"), Pair("Mahou Shoujo", "mahou-shoujo"),
            Pair("Mecha", "mecha"), Pair("Music", "music"), Pair("Mystery", "mystery"),
            Pair("Psychological", "psychological"), Pair("Romance", "romance"), Pair("Sci-Fi", "sci-fi"),
            Pair("Slice of Life", "slice-of-life"), Pair("Sports", "sports"),
            Pair("Supernatural", "supernatural"), Pair("Thriller", "thriller"),
        ),
    )

    private class LanguageFilter : UriPartFilter(
        "Language",
        arrayOf(
            Pair("<select>", ""), Pair("Hindi Dub", "hindi-dub"), Pair("Tamil Dub", "tamil-dub"),
            Pair("Telugu Dub", "telugu-dub"), Pair("English Sub", "english-sub"), Pair("English Dub", "english-dub"),
        ),
    )

    private class TypeFilter : UriPartFilter(
        "Type",
        arrayOf(
            Pair("<select>", ""), Pair("TV", "tv"), Pair("Movie", "movie"),
            Pair("OVA", "ova"), Pair("ONA", "ona"), Pair("Special", "special"),
        ),
    )

    open class UriPartFilter(displayName: String, val vals: Array<Pair<String, String>>) :
        AnimeFilter.Select<String>(displayName, vals.map { it.first }.toTypedArray()) {
        fun toUriPart() = vals[state].second
    }

    companion object {
        const val PREFIX_SEARCH = "id:"
        private const val PREF_QUALITY_KEY = "preferred_quality"
    }
}
