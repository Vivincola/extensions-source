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
import okhttp3.Headers
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class AnitubeInUa : ParsedAnimeHttpSource() {

    override val name = "AniTube"
    override val lang = "uk"
    override val supportsLatest = true

    override val baseUrl = "https://anitube.in.ua"
    private val animeUrl = "$baseUrl/anime"

    override fun headersBuilder(): Headers.Builder = super.headersBuilder()
        .add("Referer", baseUrl)
        .add("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Safari/537.36")

    private val animeSelector = "article.story"
    private val nextPageSelector = "div.navigation span.navext a"

    // ===========================
    // Popular
    // ===========================

    override fun popularAnimeRequest(page: Int): Request {
        val url = if (page == 1) animeUrl else "$animeUrl/page/$page"
        return GET(url, headers)
    }

    override fun popularAnimeSelector(): String = animeSelector

    override fun popularAnimeFromElement(element: Element): SAnime {
        val anime = SAnime.create()
        val link = element.selectFirst("div.storycla a, h2[itemprop=name] a")
            ?: element.selectFirst("a[href*='anitube.in.ua']")
            ?: throw Exception("Anime link not found")

        anime.setUrlWithoutDomain(link.attr("href"))

        val imgSpan = element.selectFirst("span.storypostimg")
        val thumbCandidate = imgSpan?.attr("data-src").orEmpty().ifBlank {
            imgSpan?.attr("src").orEmpty()
        }

        if (thumbCandidate.isNotBlank()) {
            anime.thumbnail_url = if (thumbCandidate.startsWith("http")) {
                thumbCandidate
            } else {
                "$baseUrl/${thumbCandidate.removePrefix("/")}"
            }
        }

        anime.title = element.selectFirst("span.storylink a")?.text() ?: link.text()
        return anime
    }

    override fun popularAnimeNextPageSelector(): String = nextPageSelector

    // ===========================
    // Latest
    // ===========================

    override fun latestUpdatesRequest(page: Int): Request = popularAnimeRequest(page)
    override fun latestUpdatesSelector(): String = animeSelector
    override fun latestUpdatesFromElement(element: Element): SAnime = popularAnimeFromElement(element)
    override fun latestUpdatesNextPageSelector(): String = nextPageSelector

    // ===========================
    // Search
    // ===========================

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val resultFrom = ((page - 1) * 40) + 1
        val body = FormBody.Builder()
            .add("do", "search")
            .add("subaction", "search")
            .add("fullsearch", "1")
            .add("result_from", resultFrom.toString())
            .add("story", query)
            .build()
        return POST("$baseUrl/index.php?do=search", headers, body)
    }

    override fun searchAnimeSelector(): String = animeSelector
    override fun searchAnimeFromElement(element: Element): SAnime = popularAnimeFromElement(element)
    override fun searchAnimeNextPageSelector(): String = nextPageSelector

    // ===========================
    // Details
    // ===========================

    override fun animeDetailsParse(document: Document): SAnime {
        val anime = SAnime.create()
        anime.title = document.selectFirst("meta[itemprop=name]")?.attr("content")
            ?: document.selectFirst("div.storyc h2[itemprop=name] a")?.text()
            ?: document.selectFirst("h1")?.text() ?: ""

        val imgSpan = document.selectFirst("div.storycla span.storypostimg")
        val posterCandidate = imgSpan?.attr("data-src").orEmpty().ifBlank { imgSpan?.attr("src").orEmpty() }
        if (posterCandidate.isNotBlank()) {
            anime.thumbnail_url = if (posterCandidate.startsWith("http")) {
                posterCandidate
            } else {
                "$baseUrl/${posterCandidate.removePrefix("/")}"
            }
        }

        anime.description = document.select("div.storyctext, div.storytext, div.full-text, div[itemprop=description]").text()
        val genres = mutableListOf<String>()
        document.select("div.storyinfa dt a").forEach { a ->
            val text = a.text().trim()
            if (a.attr("href").contains("xfsearch/year")) {
                anime.description = "Рік: $text\n" + (anime.description ?: "")
            } else if (text.isNotEmpty()) {
                genres.add(text)
            }
        }
        if (genres.isNotEmpty()) anime.genre = genres.joinToString(", ")

        return anime
    }

    // ===========================
    // Episodes
    // ===========================

    override fun episodeListParse(response: Response): List<SEpisode> {
        val document = response.asJsoup()
        val episodeList = mutableListOf<SEpisode>()

        // Try to find IDs for the AJAX request
        val ajaxInfo = document.selectFirst("#playlists-ajax")
        val newsId = ajaxInfo?.attr("data-news_id")
            ?: document.selectFirst("input#post_id, input[name=news_id]")?.attr("value")
            ?: document.location().substringAfterLast("/").substringBefore("-")

        val xfname = ajaxInfo?.attr("data-xfname") ?: "playlist"

        var playlistContainer = document
        if (newsId != null) {
            val ajaxUrl = "$baseUrl/engine/ajax/playlists.php?news_id=$newsId&xfield=$xfname"
            try {
                val ajaxHeaders = headersBuilder()
                    .add("X-Requested-With", "XMLHttpRequest")
                    .build()
                val ajaxResponse = client.newCall(GET(ajaxUrl, ajaxHeaders)).execute()
                val responseBody = ajaxResponse.body.string()

                if (responseBody.trim().startsWith("{")) {
                    val jsonObject = JSONObject(responseBody)
                    if (jsonObject.optBoolean("success", false)) {
                        playlistContainer = Jsoup.parse(jsonObject.getString("response"))
                    }
                } else if (responseBody.contains("li") && responseBody.contains("data-file")) {
                    playlistContainer = Jsoup.parse(responseBody)
                }
            } catch (e: Exception) { }
        }

        // Map all labels from tabs (Category, Team, Player)
        val tabsMap = playlistContainer.select(".playlists-items li[data-id]").associate {
            it.attr("data-id") to it.text().trim()
        }

        // Extract episodes
        playlistContainer.select("li[data-file]").forEach { ep ->
            val file = ep.attr("data-file")
            if (file.isBlank() || file == "null") return@forEach

            val videoUrl = when {
                file.startsWith("//") -> "https:$file"
                file.startsWith("/") -> "$baseUrl$file"
                else -> file
            }

            val epName = ep.ownText().ifBlank { ep.text() }.trim()
            val dataId = ep.attr("data-id")
            val parts = dataId.split("_")

            // Labels based on data-id parts (cat_team_player_ep)
            val catId = parts.take(2).joinToString("_")
            val teamId = parts.take(3).joinToString("_")
            val playerId = parts.take(4).joinToString("_")

            val catName = tabsMap[catId] ?: ""
            val teamName = tabsMap[teamId] ?: ""
            val playerName = tabsMap[playerId] ?: ""

            val labelParts = mutableListOf<String>()
            if (catName.isNotBlank() && catName != epName) labelParts.add(catName)
            if (teamName.isNotBlank() && teamName != epName) labelParts.add(teamName)
            if (playerName.isNotBlank() && !playerName.contains("Плеєр") && playerName != epName) labelParts.add(playerName)
            // If playerName contains "Плеєр", we might still want it if it's the only differentiator
            if (playerName.contains("Плеєр")) labelParts.add(playerName)

            val label = labelParts.distinct().joinToString(" - ")

            episodeList.add(
                SEpisode.create().apply {
                    name = if (label.isNotBlank()) "$epName ($label)" else epName
                    url = videoUrl
                    episode_number = epName.filter { it.isDigit() || it == '.' }.toFloatOrNull() ?: 1f
                },
            )
        }

        return episodeList.distinctBy { it.name + it.url }.reversed()
    }

    override fun episodeListSelector(): String = throw UnsupportedOperationException()
    override fun episodeFromElement(element: Element): SEpisode = throw UnsupportedOperationException()

    // ===========================
    // Video extraction
    // ===========================

    override suspend fun getVideoList(episode: SEpisode): List<Video> {
        val url = episode.url
        if (url.isBlank()) return emptyList()

        if (url.contains(".m3u8")) {
            return extractVideosFromM3u8(url, url)
        }

        return extractVideosFromUrl(url)
    }

    private fun extractVideosFromUrl(url: String, referer: String = baseUrl): List<Video> {
        val list = mutableListOf<Video>()
        val requestHeaders = headersBuilder().set("Referer", referer).build()

        try {
            val response = client.newCall(GET(url, requestHeaders)).execute()
            val html = response.body.string()

            // Look for m3u8 sources in script or attributes
            val m3u8Regex = """["'](https?:[^"']+\.m3u8[^"']*)["']""".toRegex()
            m3u8Regex.findAll(html).forEach { match ->
                val m3u8Url = match.groupValues[1].replace("\\/", "/")
                list.addAll(extractVideosFromM3u8(m3u8Url, url))
            }

            // Recursively search in iframes
            if (list.isEmpty()) {
                val doc = Jsoup.parse(html, url)
                doc.select("iframe").forEach { iframe ->
                    val iframeUrl = iframe.attr("abs:src")
                    if (iframeUrl.isNotBlank() && iframeUrl != url) {
                        list.addAll(extractVideosFromUrl(iframeUrl, url))
                    }
                }
            }

            // Direct mp4 fallback
            if (list.isEmpty()) {
                val mp4Regex = """["'](https?:[^"']+\.mp4[^"']*)["']""".toRegex()
                mp4Regex.findAll(html).forEach { match ->
                    val mp4Url = match.groupValues[1].replace("\\/", "/")
                    list.add(Video(mp4Url, "Direct (mp4)", mp4Url, headers = requestHeaders))
                }
            }
        } catch (e: Exception) { }

        return list.distinctBy { it.url }
    }

    private fun extractVideosFromM3u8(m3u8Url: String, referer: String): List<Video> {
        val videoHeaders = headersBuilder().set("Referer", referer).build()
        return try {
            val response = client.newCall(GET(m3u8Url, videoHeaders)).execute()
            val masterPlaylist = response.body.string()
            val list = mutableListOf<Video>()

            if (masterPlaylist.contains("#EXT-X-STREAM-INF")) {
                masterPlaylist.split("#EXT-X-STREAM-INF:").drop(1).forEach { line ->
                    val quality = line.substringAfter("RESOLUTION=", "").substringAfter("x", "").substringBefore(",", "Default") + "p"
                    var vUrl = line.substringAfter("\n").substringBefore("\n").trim()
                    if (!vUrl.startsWith("http")) {
                        vUrl = m3u8Url.substringBeforeLast("/") + "/" + vUrl
                    }
                    list.add(Video(vUrl, quality, vUrl, headers = videoHeaders))
                }
            } else if (masterPlaylist.contains("#EXTINF") || masterPlaylist.contains("#EXTM3U")) {
                list.add(Video(m3u8Url, "Default", m3u8Url, headers = videoHeaders))
            }
            list
        } catch (e: Exception) {
            emptyList()
        }
    }

    override fun videoListSelector(): String = throw UnsupportedOperationException()
    override fun videoFromElement(element: Element): Video = throw UnsupportedOperationException()
    override fun videoUrlParse(document: Document): String = throw UnsupportedOperationException()

    override fun getFilterList(): AnimeFilterList = AnimeFilterList()
}
