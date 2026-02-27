package eu.kanade.tachiyomi.animeextension.uk.anitubeinua

import android.net.Uri
import android.util.Log
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

    override val name = "Anitube.in.ua"
    override val baseUrl = "https://anitube.in.ua"
    override val lang = "uk"

    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/anime/", headers)

    override fun popularAnimeSelector() = "div.anime-card, .anime-item, article"

    override fun popularAnimeFromElement(element: Element): AnimeInfo {
        return AnimeInfo().apply {
            val aTag = element.selectFirst("a") ?: throw Exception("No link found")
            name = aTag.selectFirst("img")?.attr("alt") ?: aTag.text()
            url = aTag.attr("href").let { relativeLinkToAbsolute(it) }
            image = element.selectFirst("img")?.attr("src") ?: ""
        }
    }

    override fun searchAnimeRequest(query: String, page: Int, filters: AnimeFilters): Request {
        return GET("$baseUrl/search/?q=${query.replace(" ", "+")}", headers)
    }

    override fun searchAnimeSelector() = popularAnimeSelector()

    override fun searchAnimeFromElement(element: Element): AnimeInfo = popularAnimeFromElement(element)

    override fun episodeListParse(response: Response): List<EpisodeInfo> {
        val document = response.asJsoup()
        return document.select("div.episode-item, .episode, li.episode").map { episode ->
            EpisodeInfo().apply {
                episode.selectFirst("a")?.let { a ->
                    name = a.text()
                    url = a.attr("href").let { relativeLinkToAbsolute(it) }
                }
                date_upload = episode.selectFirst("time, .date")?.text()
            }
        }
    }

    override fun episodeListSelector() = "div.episode-item, .episode, li"

    override fun videoListParse(response: Response): List<VideoInfo> {
        // Extract video URLs from player or m3u8
        val document = response.asJsoup()
        val videos = mutableListOf<VideoInfo>()
        
        // Look for m3u8 or mp4 sources
        document.select("video source, .player source").forEach { source ->
            source.attr("src").takeIf { it.endsWith(".m3u8") || it.endsWith(".mp4") }?.let { url ->
                videos.add(VideoInfo().apply {
                    key = "Auto"
                    url = url
                })
            }
        }
        
        // Fallback: iframe or player data
        document.select("iframe[src*='m3u8'], .player").firstOrNull()?.let { player ->
            player.attr("src").takeIf { it.contains("m3u8") }?.let { url ->
                videos.add(VideoInfo().apply {
                    key = "Auto"
                    url = url
                })
            }
        }
        
        return videos
    }

    override fun videoListSelector() = "video source"

    override fun videoUrlParse(document: org.jsoup.nodes.Document): String {
        // Return first available video URL
        return videoListParse(GET(document.location(), headers)).firstOrNull()?.url ?: ""
    }

    private val headers by lazy {
        Headers.Builder()
            .add("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
            .add("Referer", baseUrl)
            .build()
    }
}
