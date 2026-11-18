package com.donghua.tv.data

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

object Parser {

    fun abs(url: String?): String =
        if (url.isNullOrBlank()) ""
        else if (url.startsWith("http")) url
        else Api.BASE + url

    fun parseCard(el: Element): MediaCard? {
        val a = el.selectFirst("a[href]") ?: return null
        val href = a.attr("href")
        if (!href.startsWith("/ver/") && !href.startsWith("/donghua/") && !href.startsWith("http")) return null
        val title = el.selectFirst(".md-card-title")?.text() ?: return null
        val img = el.selectFirst("img")
        var date: String? = null
        var views: String? = null
        el.select(".md-card-meta span").forEach { s ->
            val t = s.text().trim()
            if (t.matches(Regex("\\d{2}-\\d{2}-\\d{4}"))) date = t
            else if (t.replace(",", "").replace(".", "").all { it.isDigit() } && t.isNotEmpty()) views = t
        }
        val badge = el.selectFirst(".md-card-badge")?.text()
        val limited = el.hasClass("limited") || el.select(".md-card-meta span").any { it.text().contains("Limitado") }
        // Jsoup's attr() returns "" for a missing attribute, never null, so a
        // plain `?:` short-circuits on the empty string and data-src is
        // never reached. The site lazy-loads most of its thumbnails through
        // data-src, so this silently blanked a large share of the artwork.
        val thumb = img?.attr("src")?.ifBlank { img.attr("data-src") } ?: ""
        return MediaCard(
            title = title,
            thumb = abs(thumb),
            // Episodes are requested in their canonical form so the page always
            // lists the direct HLS server, not just the web embeds.
            url = if (href.contains("/ver/")) canonicalEpisodeUrl(href) else href,
            date = date,
            views = views,
            badge = badge,
            limited = limited,
        )
    }

    fun parseCardsHtml(html: String): List<MediaCard> {
        if (html.isBlank()) return emptyList()
        return Jsoup.parse(html, Api.BASE).select(".md-card").mapNotNull { parseCard(it) }
    }

    fun parseCards(doc: Document): List<MediaCard> =
        doc.select(".md-card").mapNotNull { parseCard(it) }

    fun parseHome(doc: Document): HomeData {
        fun sectionCards(titleText: String): List<MediaCard> {
            val section = doc.select("section.md-section").firstOrNull { s ->
                s.selectFirst(".md-section-title")?.text()?.contains(titleText, ignoreCase = true) == true
            } ?: return emptyList()
            return section.select(".md-card").mapNotNull { parseCard(it) }
        }

        val limited = sectionCards("Episodios Limitados")
        val nuevos = sectionCards("Nuevos Episodios")

        val tabs = doc.select(".md-tab").map { it.text().trim() }.filter { it.isNotBlank() }
        val panes = doc.select(".md-tab-pane")
        val tabCards = mutableMapOf<String, List<MediaCard>>()
        tabs.forEachIndexed { i, tab ->
            val pane = panes.getOrNull(i) ?: return@forEachIndexed
            tabCards[tab] = pane.select(".md-card").mapNotNull { parseCard(it) }
        }
        return HomeData(limited, nuevos, tabs, tabCards)
    }

    fun parseList(doc: Document, path: String, page: Int): ListPage {
        val cards = parseCards(doc)
        val nums = doc.select("a[href]").mapNotNull { a ->
            val h = a.attr("href")
            if (h.startsWith(path)) {
                h.substring(path.length).removePrefix("/").toIntOrNull()
            } else null
        }
        val maxPage = (nums.maxOrNull() ?: 1).coerceAtLeast(page)
        return ListPage(cards, page, maxPage)
    }

    fun parseSearch(doc: Document): List<MediaCard> {
        val text = doc.text()
        if (text.contains("no se encontraron", ignoreCase = true) ||
            text.contains("sin resultados", ignoreCase = true)
        ) return emptyList()
        return parseCards(doc)
    }

    /**
     * Pulls the episode number out of an episode href.
     *
     * The site uses two shapes: the plain `/ver/{slug}/{n}`, and a tokenised
     * `/ver/{slug}/{n}/{base64}` that the home feed links to for newer
     * episodes. Reading the last path segment therefore returned the token
     * instead of the number, which silently dropped those episodes from the
     * chapter list. The number is always the segment after the slug, so it is
     * matched positionally rather than by position from the end.
     */
    fun episodeNumberOf(href: String): Int? {
        val path = href.substringBefore('?').trimEnd('/')
        val parts = path.split('/').filter { it.isNotEmpty() }
        val verIdx = parts.indexOfLast { it == "ver" || it == "capitulo" }
        val afterSlug = if (verIdx >= 0) parts.drop(verIdx + 2) else parts
        return afterSlug.firstOrNull { it.toIntOrNull() != null }?.toIntOrNull()
    }

    /**
     * Reduces an episode href to the canonical `/ver/{slug}/{n}` form.
     *
     * This is not cosmetic. The two forms are not equivalent: measured across
     * several episodes, the tokenised link served by the home feed omits the
     * direct HLS server (and the Tamamo key) entirely, leaving only web embeds
     * that need a browser and are frequently dead. The plain form always served
     * the full set, and is the form the series page itself links to. Since the
     * token carries nothing the plain URL needs, it is dropped.
     *
     * Non-episode links are returned untouched.
     */
    fun canonicalEpisodeUrl(href: String): String {
        if (href.isBlank()) return href
        val query = href.substringAfter('?', "")
        val path = href.substringBefore('?').trimEnd('/')
        val parts = path.split('/').filter { it.isNotEmpty() }
        val verIdx = parts.indexOfLast { it == "ver" || it == "capitulo" }
        if (verIdx < 0) return href
        val slug = parts.getOrNull(verIdx + 1) ?: return href
        val number = episodeNumberOf(href) ?: return href
        val rebuilt = "/ver/$slug/$number"
        return if (query.isEmpty()) rebuilt else "$rebuilt?$query"
    }

    fun parseDetail(doc: Document): SeriesDetail {
        val title = doc.selectFirst(".md-detail-title")?.text() ?: ""
        val poster = abs(doc.selectFirst(".md-detail-poster img")?.attr("src"))
        val background = abs(doc.selectFirst(".md-detail-banner-bg img")?.attr("src"))
        val synopsis = doc.selectFirst(".md-detail-synopsis")?.text()?.takeIf {
            it.isNotBlank() && it != "."
        }
        val genres = doc.select(".md-genre-tag").map { it.text() }.filter { it.isNotBlank() }
        val info = doc.select(".md-info-item").mapNotNull { el ->
            val strong = el.selectFirst("strong")?.text() ?: return@mapNotNull null
            val label = strong.removeSuffix(":").trim()
            val value = el.ownText().trim()
            if (label.isEmpty() || value.isEmpty()) null else label to value
        }
        val episodeCount = doc.selectFirst(".md-ep-count")?.text()
        val episodes = doc.select(".md-episode-item").mapNotNull { item ->
            val a = item.selectFirst("a[href]") ?: return@mapNotNull null
            val href = a.attr("href")
            val num = episodeNumberOf(href) ?: return@mapNotNull null
            // Read from the item rather than the anchor: the artwork sits in a
            // sibling div, so looking only inside the link found nothing.
            val thumb = item.selectFirst(".md-episode-thumb img")?.let { img ->
                abs(img.attr("src").ifBlank { img.attr("data-src") })
            }
            Episode(num, canonicalEpisodeUrl(href), thumb?.takeIf { it.isNotBlank() })
        }.distinctBy { it.number }.sortedBy { it.number }
        return SeriesDetail(title, poster, background, synopsis, genres, info, episodeCount, episodes)
    }
}
