package com.donghua.tv.data

data class MediaCard(
    val title: String,
    val thumb: String,
    val url: String,
    val date: String? = null,
    val views: String? = null,
    val badge: String? = null,
    val limited: Boolean = false,
) {
    val isEpisode: Boolean get() = url.contains("/ver/") || url.contains("/capitulo/")
    val episodeNumber: Int get() = Parser.episodeNumberOf(url) ?: 0
    val targetUrl: String get() = if (url.startsWith("http")) url else Api.BASE + url
}

/**
 * One chapter of a series.
 *
 * [thumb] is the artwork the series page carries for the chapter. In practice
 * every chapter of a series points at the same series poster, so the picture
 * alone does not identify anything; the number and the resume progress do that,
 * which is why the grid leans on them rather than on the image.
 */
data class Episode(
    val number: Int,
    val url: String,
    val thumb: String? = null,
)

data class SeriesDetail(
    val title: String,
    val poster: String?,
    val background: String?,
    val synopsis: String?,
    val genres: List<String>,
    val info: List<Pair<String, String>>,
    val episodeCount: String?,
    val episodes: List<Episode>,
)

data class HomeData(
    val limited: List<MediaCard>,
    val nuevos: List<MediaCard>,
    val tabs: List<String>,
    val tabCards: Map<String, List<MediaCard>>,
)

data class ListPage(
    val cards: List<MediaCard>,
    val currentPage: Int,
    val maxPage: Int,
)
