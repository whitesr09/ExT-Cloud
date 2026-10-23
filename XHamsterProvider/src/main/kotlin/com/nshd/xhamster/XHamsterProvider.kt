package com.nshd.xhamster

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.lagradost.cloudstream3.Actor
import com.lagradost.cloudstream3.ActorData
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.MovieSearchResponse
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import java.net.URLEncoder

class XHamsterProvider : MainAPI() {
    override var mainUrl = "https://xhamster46.desi"
    override var name = "XHamster"
    override val supportedTypes = setOf(TvType.NSFW)
    override var lang = "en"
    override val hasMainPage = true

    // The site only server-renders its data (window.initials JSON) for desktop UAs.
    // A mobile UA gets an empty JS shell, so every request uses a desktop UA.
    private val headers = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Safari/537.36"
    )

    private val mapper = ObjectMapper()

    override val mainPage = mainPageOf(
        "" to "Latest",
        "newest" to "Newest",
        "best" to "Best",
        "most-viewed" to "Most Viewed",
    )

    /**
     * Fetches a page and parses its window.initials JSON.
     * The site serves a bare page when the session has no cookies yet,
     * so if the expected data is missing we warm up on the homepage
     * (which sets the cookies) and retry once.
     */
    private suspend fun fetchInitials(url: String, expect: (JsonNode) -> Boolean): JsonNode? {
        suspend fun tryFetch(): JsonNode? {
            val text = app.get(url, headers = headers).text
            val node = parseInitials(text)
            return if (node != null && expect(node)) node else null
        }
        tryFetch()?.let { return it }
        // Warm-up: homepage visit sets the session cookies the video/listing pages need.
        runCatching { app.get(mainUrl, headers = headers) }
        return tryFetch()
    }

    private fun parseInitials(html: String): JsonNode? {
        val marker = "window.initials="
        val start = html.indexOf(marker)
        if (start < 0) return null
        val jsonStart = html.indexOf('{', start)
        val end = html.indexOf(";</script>", jsonStart)
        if (jsonStart < 0 || end < 0) return null
        return runCatching { mapper.readTree(html.substring(jsonStart, end)) }.getOrNull()
    }

    /** Recursively collects every videoThumbProps item on the page, deduped by URL. */
    private fun collectVideos(root: JsonNode): List<JsonNode> {
        val byUrl = LinkedHashMap<String, JsonNode>()
        fun walk(node: JsonNode) {
            if (node.isObject) {
                val list = node.get("videoThumbProps")
                if (list != null && list.isArray) {
                    for (item in list) {
                        val url = item.get("pageURL")?.asText().orEmpty()
                        if (url.isNotBlank()) byUrl.putIfAbsent(url, item)
                    }
                }
                node.fields().forEach { (_, v) -> walk(v) }
            } else if (node.isArray) {
                node.forEach { walk(it) }
            }
        }
        walk(root)
        return byUrl.values.toList()
    }

    private fun JsonNode.toSearchResponse(): MovieSearchResponse? {
        val title = get("title")?.asText()?.trim().orEmpty()
        val url = get("pageURL")?.asText().orEmpty()
        if (title.isBlank() || url.isBlank()) return null
        return newMovieSearchResponse(title, url, TvType.NSFW) {
            posterUrl = get("thumbURL")?.asText()
        }
    }

    private fun hasVideos(node: JsonNode): Boolean = collectVideos(node).isNotEmpty()

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val base = if (request.data.isBlank()) mainUrl else "$mainUrl/${request.data}"
        val url = if (page <= 1) base else "$base?page=$page"
        val node = fetchInitials(url, ::hasVideos)
        val items = node?.let { collectVideos(it) }.orEmpty().mapNotNull { it.toSearchResponse() }
        return newHomePageResponse(request.name, items, hasNext = items.isNotEmpty())
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val node = fetchInitials("$mainUrl/search/$encoded", ::hasVideos) ?: return emptyList()
        return collectVideos(node).mapNotNull { it.toSearchResponse() }
    }

    override suspend fun load(url: String): LoadResponse? {
        val node = fetchInitials(url) { it.has("videoModel") } ?: return null
        val model = node.get("videoModel")
        val title = model?.get("title")?.asText()?.trim().orEmpty()
        if (title.isBlank()) return null

        val entity = node.get("videoEntity")
        val actors = entity?.get("pornstarModels")?.mapNotNull { star ->
            star.get("name")?.asText()?.takeIf { it.isNotBlank() }
                ?.let { ActorData(Actor(it)) }
        } ?: emptyList()

        val related = node.get("relatedVideosComponent")
            ?.get("videoTabInitialData")
            ?.get("videoListProps")
            ?.get("videoThumbProps")
            ?.mapNotNull { it.toSearchResponse() }
            ?: emptyList()

        val durationSec = model?.get("duration")?.asText()?.toIntOrNull()

        return newMovieLoadResponse(title, url, TvType.NSFW, url) {
            posterUrl = model?.get("thumbURL")?.asText()
            plot = model?.get("description")?.asText()
            this.duration = durationSec?.let { it / 60 }
            this.actors = actors
            this.recommendations = related
        }
    }

    /** Recursively finds the node whose "sources" object holds the mp4 quality map. */
    private fun findMp4Sources(node: JsonNode): JsonNode? {
        if (node.isObject) {
            val mp4 = node.get("sources")?.get("mp4")
            if (mp4 != null && mp4.isObject) return mp4
            node.fields().forEach { (_, v) ->
                findMp4Sources(v)?.let { return it }
            }
        } else if (node.isArray) {
            node.forEach { child -> findMp4Sources(child)?.let { return it } }
        }
        return null
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val node = fetchInitials(data) { findMp4Sources(it) != null } ?: return false
        val mp4 = findMp4Sources(node) ?: return false
        var found = false
        mp4.fields().forEach { (label, linkNode) ->
            val link = linkNode.asText()
            if (link.isBlank()) return@forEach
            val quality = label.removeSuffix("p").toIntOrNull() ?: 0
            callback.invoke(
                newExtractorLink(name, "$name $label", link, ExtractorLinkType.VIDEO) {
                    referer = "$mainUrl/"
                    this.quality = quality
                }
            )
            found = true
        }
        return found
    }
}
