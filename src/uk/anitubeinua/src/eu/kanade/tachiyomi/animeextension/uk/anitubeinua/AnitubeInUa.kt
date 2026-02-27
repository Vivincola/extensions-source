package eu.kanade.tachiyomi.animeextension.uk.anitubeinua

import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.FormBody
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class AnitubeInUa : ParsedAnimeHttpSource() {

    override val name = "AniTube"
    override val lang = "uk"
    override val supportsLatest = true

    override val baseUrl = "https://anitube.in.ua"
    private val animeUrl = "$baseUrl/anime"

    private val animeSelector = "article.story"
    private val nextPageSelector = "div.navigation span.navext a"

    // ===========================
    // Popular
    // ===========================

    override fun popularAnimeRequest(page: Int): Request {
        // AniTube uses /anime/page/N navigation for listing pages.
        val url = if (page == 1) {
            animeUrl
        } else {
            "$animeUrl/page/$page"
        }
        return GET(url, headers)
    }

    override fun popularAnimeSelector(): String = animeSelector

    override fun popularAnimeFromElement(element: Element): SAnime {
        val anime = SAnime.create()

        // Main link to the anime details page.
        val link = element.selectFirst("div.storycla a, h2[itemprop=name] a")
            ?: element.selectFirst("a[href*='anitube.in.ua']")
            ?: throw Exception("Anime link not found")

        anime.setUrlWithoutDomain(link.attr("href"))

        // Thumbnail from story card image.
        val imgSpan = element.selectFirst("span.storypostimg")
        val thumbCandidate = imgSpan?.attr("data-src").orEmpty().ifBlank {
            imgSpan?.attr("src").orEmpty()
        }

        if (thumbCandidate.isNotBlank()) {
            anime.thumbnail_url = if (thumbCandidate.startsWith("http")) {
                thumbCandidate
            } else {
                // AniTube paths are relative to the site root.
                if (thumbCandidate.startsWith("/")) {
                    "$baseUrl$thumbCandidate"
                } else {
                    "$baseUrl/$thumbCandidate"
                }
            }
        }

        // Title from the card; fallback to link text.
        val title = element.selectFirst("span.storylink a")?.text()
            ?: link.text()

        anime.title = title

        return anime
    }

    override fun popularAnimeNextPageSelector(): String = nextPageSelector

    // ===========================
    // Latest
    // ===========================

    override fun latestUpdatesRequest(page: Int): Request {
        // Latest updates use the same listing with different sort on the site.
        // If needed, you can add query parameters here.
        return popularAnimeRequest(page)
    }

    override fun latestUpdatesSelector(): String = animeSelector

    override fun latestUpdatesFromElement(element: Element): SAnime =
        popularAnimeFromElement(element)

    override fun latestUpdatesNextPageSelector(): String = nextPageSelector

    // ===========================
    // Search
    // ===========================

    override fun searchAnimeRequest(
        page: Int,
        query: String,
        filters: AnimeFilterList,
    ): Request {
        // AniTube uses DLE search at /index.php?do=search.
        // result_from is 1-based index of the first result; 40 per page is a common default.
        val resultFrom = ((page - 1) * 40) + 1

        val body = FormBody.Builder()
            .add("do", "search")
            .add("subaction", "search")
            .add("fullsearch", "1")
            .add("result_from", resultFrom.toString())
            .add("story", query)
            .build()

        val url = "$baseUrl/index.php?do=search"
        return POST(url, headers, body)
    }

    override fun searchAnimeSelector(): String = animeSelector

    override fun searchAnimeFromElement(element: Element): SAnime =
        popularAnimeFromElement(element)

    override fun searchAnimeNextPageSelector(): String = nextPageSelector

    // ===========================
    // Details
    // ===========================

    override fun animeDetailsParse(document: Document): SAnime {
        val anime = SAnime.create()

        // Title: prefer schema.org meta, then visible title.
        val title = document.selectFirst("meta[itemprop=name]")?.attr("content")
            ?: document.selectFirst("div.storyc h2[itemprop=name] a")?.text()
            ?: document.selectFirst("h1")?.text()
            ?: ""

        anime.title = title

        // Poster: reuse the story card image if present on the page.
        val imgSpan = document.selectFirst("div.storycla span.storypostimg")
        val posterCandidate = imgSpan?.attr("data-src").orEmpty().ifBlank {
            imgSpan?.attr("src").orEmpty()
        }

        if (posterCandidate.isNotBlank()) {
            anime.thumbnail_url = if (posterCandidate.startsWith("http")) {
                posterCandidate
            } else {
                if (posterCandidate.startsWith("/")) {
                    "$baseUrl$posterCandidate"
                } else {
                    "$baseUrl/$posterCandidate"
                }
            }
        }

        // Description block.
        anime.description = document.select("div.storyctext").text()

        // Year + genres from info block links.
        val infoLinks = document.select("div.storyinfa dt a")
        val genres = mutableListOf<String>()
        var year: String? = null

        for (a in infoLinks) {
            val href = a.attr("href")
            when {
                href.contains("xfsearch/year") -> {
                    year = a.text()
                }
                // Treat other labeled links as genres.
                else -> {
                    val text = a.text().trim()
                    if (text.isNotEmpty()) {
                        genres += text
                    }
                }
            }
        }

        if (genres.isNotEmpty()) {
            anime.genre = genres.joinToString(", ")
        }

        // SAnime does not have a 'year' property in this version of the library.
        // If year is important, it could be appended to the description.
        year?.let {
            anime.description = "Рік: $it\n" + (anime.description ?: "")
        }

        return anime
    }

    // ===========================
    // Episodes
    // ===========================

    override fun episodeListParse(response: Response): List<SEpisode> {
        // NOTE: This is a minimal placeholder implementation.
        // AniTube’s real episodes are managed by its player/JS; you should
        // inspect the anime page to extract the per-episode playlist and
        // replace this block with proper parsing.

        val doc = response.asJsoup()

        val episode = SEpisode.create().apply {
            name = "Episode 1"
            setUrlWithoutDomain(doc.location())
            episode_number = 1F
        }

        return listOf(episode)
    }

    override fun episodeListSelector(): String =
        throw UnsupportedOperationException("Not used; episodeListParse is overridden")

    override fun episodeFromElement(element: Element): SEpisode =
        throw UnsupportedOperationException("Not used; episodeListParse is overridden")

    // ===========================
    // Video extraction
    // ===========================

    override suspend fun getVideoList(episode: SEpisode): List<Video> {
        // WARNING: This is intentionally naive and likely needs to be
        // replaced with real player extraction for AniTube.
        //
        // Strategy to improve it:
        // 1. Open an anime page in a browser.
        // 2. Look for <iframe>, <video>, or JS variables that contain m3u8/mp4 URLs.
        // 3. Reproduce that logic here (potentially with an extra request).

        val url = if (episode.url.startsWith("http")) {
            episode.url
        } else {
            "$baseUrl${episode.url}"
        }

        val doc = client.newCall(GET(url, headers)).execute().asJsoup()

        // Try to grab the first obvious video-like URL.
        val candidate = doc.selectFirst(
            "source[src*='.m3u8'], source[src*='.mp4'], a[href*='.m3u8'], a[href*='.mp4']",
        )?.attr("src") ?: return emptyList()

        val videoUrl = if (candidate.startsWith("http")) {
            candidate
        } else {
            if (candidate.startsWith("/")) {
                "$baseUrl$candidate"
            } else {
                "$baseUrl/$candidate"
            }
        }

        val video = Video(videoUrl, "Default", videoUrl)
        return listOf(video)
    }

    override fun videoListSelector(): String =
        throw UnsupportedOperationException("Not used; getVideoList is overridden")

    override fun videoFromElement(element: Element): Video =
        throw UnsupportedOperationException("Not used; getVideoList is overridden")

    override fun videoUrlParse(document: Document): String =
        throw UnsupportedOperationException("Not used; getVideoList is overridden")

    // ===========================
    // Filters
    // ===========================

    override fun getFilterList(): AnimeFilterList = AnimeFilterList()
}
