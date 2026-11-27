/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.wizestream.plugin.pornhub

import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.Locale
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.schabi.newpipe.extractor.ListExtractor.InfoItemsPage
import org.schabi.newpipe.extractor.Page
import org.schabi.newpipe.extractor.exceptions.ParsingException
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.extractor.stream.StreamInfoItemsCollector

/** Shared HTML parsing for the public video listing and search result pages. */
internal object PornHubCatalog {
    fun parse(
        document: Document,
        pageUrl: String,
        serviceId: Int,
        containerId: String
    ): InfoItemsPage<StreamInfoItem> {
        val finalUrl = validatePage(document, pageUrl)
        val container = document.getElementById(containerId)
        if (container == null) {
            if (document.selectFirst(".noResults, .no-results, .no_results, #noResults") != null) {
                return InfoItemsPage.emptyPage()
            }
            throw ParsingException("PornHub returned an unrecognized video listing")
        }
        return parseCards(container, "li.videoblock", serviceId) { nextPage(document, finalUrl) }
    }

    /** Profile lists have different containers from search and the general video catalog. */
    fun parseChannel(
        document: Document,
        pageUrl: String,
        serviceId: Int,
        channelId: String
    ): InfoItemsPage<StreamInfoItem> {
        val finalUrl = validateChannelContinuation(document.location().ifBlank { pageUrl }, pageUrl, minimumPage = 1)
        if (pageNumber(parameters(URI(finalUrl))) != pageNumber(parameters(URI(pageUrl)))) {
            throw ParsingException("PornHub redirected the requested channel listing page")
        }
        document.selectFirst("link[rel=canonical][href]")?.absUrl("href")?.takeIf(String::isNotBlank)?.let {
            if (!PornHubUrls.sameChannel(channelId, PornHubUrls.channelId(it))) {
                throw ParsingException("PornHub returned a different channel video listing")
            }
        }
        val container = if (channelId.startsWith("channels/")) {
            document.getElementById("showAllChanelVideos")
        } else {
            document.getElementById("mostRecentVideosSection") ?: document.selectFirst("div.profileVids")
        }
        if (container == null) {
            if (PornHubChannelMetadata.hasProfileStructure(document) &&
                document.selectFirst(".noResults, .no-results, .no_results, #noResults") != null
            ) return InfoItemsPage.emptyPage()
            throw ParsingException("PornHub returned an unrecognized channel video listing")
        }
        val cards = if (channelId.startsWith("channels/")) {
            "li[data-video-vkey], li.pcVideoListItem, li.videoBox, li.videoblock"
        } else {
            "li.pcVideoListItem, li.videoBox, li.videoblock"
        }
        return parseCards(container, cards, serviceId) { nextPage(document, finalUrl, allowLoadMore = true) }
    }

    private fun validatePage(document: Document, pageUrl: String): String {
        val finalUrl = validateContinuation(document.location().ifBlank { pageUrl }, pageUrl, minimumPage = 1)
        if (pageNumber(parameters(URI(finalUrl))) != pageNumber(parameters(URI(pageUrl)))) {
            throw ParsingException("PornHub redirected the requested listing page")
        }
        return finalUrl
    }

    private fun parseCards(
        container: Element,
        cardSelector: String,
        serviceId: Int,
        nextPage: () -> Page?
    ): InfoItemsPage<StreamInfoItem> {
        val collector = StreamInfoItemsCollector(serviceId)
        val ids = mutableSetOf<String>()
        for (card in container.select(cardSelector)) {
            val item = PornHubStreamItemExtractor(card)
            if (item.isAd()) continue
            val id = try {
                PornHubUrls.streamId(item.getUrl())
            } catch (_: ParsingException) {
                // Advertisements and premium placeholders can occupy otherwise normal cards.
                continue
            }
            if (id in ids) continue
            val previousCount = collector.items.size
            collector.commit(item)
            if (collector.items.size > previousCount) ids.add(id)
        }
        if (collector.items.isEmpty() && collector.errors.isNotEmpty()) {
            throw ParsingException("PornHub video cards could not be parsed", collector.errors.first())
        }
        val continuation = try {
            nextPage()
        } catch (error: ParsingException) {
            // A broken Next control must not discard the valid cards already fetched.
            // Keep the rejection visible and stop pagination; never fetch the rejected URL.
            if (collector.items.isEmpty()) throw error
            return InfoItemsPage(collector.items, null, collector.errors + error)
        }
        return InfoItemsPage(collector, continuation)
    }

    /** Validate a saved or HTML-provided continuation before making any network request. */
    fun validateContinuation(candidate: String?, initialUrl: String, minimumPage: Int = 2): String {
        if (candidate.isNullOrBlank()) throw ParsingException("PornHub page is missing a URL")
        val valid = PornHubUrls.siteUrl(candidate)
        if (URI(candidate).fragment != null) throw ParsingException("PornHub pagination contains a fragment")
        val next = URI(valid)
        val initial = URI(PornHubUrls.siteUrl(initialUrl))
        val nextQuery = parameters(next)
        val initialQuery = parameters(initial)
        val page = pageNumber(nextQuery)
        // siteUrl has checked both hosts against the provider allowlist. A redirect between
        // its desktop/mobile/language hosts does not change the selected listing.
        if (listingPath(next) != listingPath(initial)) {
            throw ParsingException("PornHub pagination changed the listing path")
        }
        val nextIdentity = listingParameters(next, nextQuery)
        val initialIdentity = listingParameters(initial, initialQuery)
        if (nextIdentity != initialIdentity) {
            val changedKeys = (nextIdentity.keys + initialIdentity.keys)
                .filter { nextIdentity[it] != initialIdentity[it] }.sorted()
            // Include parameter names only: search text and other query values stay private.
            throw ParsingException("PornHub pagination changed listing parameters: " + changedKeys.joinToString(", "))
        }
        if (page < minimumPage) throw ParsingException("PornHub pagination did not advance to the requested page")
        return valid
    }

    private fun listingPath(uri: URI): String = when (uri.path) {
        "/video/", "/video/search/" -> uri.path.removeSuffix("/")
        else -> uri.path
    }

    private fun listingParameters(uri: URI, query: Map<String, String>): Map<String, String> =
        query.filterKeys { it != "page" }.mapValues { (key, value) ->
            // Keyword searches can be canonicalized to lowercase by the provider. Do not
            // case-fold channel slugs, filters, or the actual URL sent to the downloader.
            if (listingPath(uri) == "/video/search" && key == "search") value.lowercase(Locale.ROOT) else value
        }

    /** Model and performer profile aliases may change only the kind, never the slug or list. */
    fun validateChannelContinuation(candidate: String?, initialUrl: String, minimumPage: Int = 2): String {
        if (candidate.isNullOrBlank()) throw ParsingException("PornHub page is missing a URL")
        val next = URI(PornHubUrls.siteUrl(candidate))
        val initial = URI(PornHubUrls.siteUrl(initialUrl))
        val expectedId = PornHubUrls.channelId(initialUrl)
        val actualId = PornHubUrls.channelId(candidate)
        if (!PornHubUrls.sameChannel(expectedId, actualId) ||
            next.path != URI(PornHubUrls.channelVideosUrl(actualId)).path ||
            initial.path != URI(PornHubUrls.channelVideosUrl(expectedId)).path
        ) throw ParsingException("PornHub pagination changed the selected channel or video tab")
        val aliasInitialUrl = "${initial.scheme}://${initial.rawAuthority}${next.rawPath}" +
            initial.rawQuery?.let { "?$it" }.orEmpty()
        return validateContinuation(candidate, aliasInitialUrl, minimumPage)
    }

    private fun nextPage(document: Document, currentUrl: String, allowLoadMore: Boolean = false): Page? {
        val marker = document.selectFirst("li.page_next")
        if (marker?.hasClass("disabled") == true || marker?.attr("aria-disabled") == "true") return null
        val link = marker?.selectFirst("a[href]")
            ?: document.selectFirst("a[rel=next][href], link[rel=next][href]")
            ?: return if (allowLoadMore) loadMorePage(document, currentUrl) else null
        if (link.hasClass("disabled") || link.attr("aria-disabled") == "true") return null
        val href = link.attr("href").trim()
        if (href.isEmpty()) return null
        val resolved = try {
            // URI.resolve treats a query-only reference as a directory path on the JVM.
            if (href.startsWith('?')) currentUrl.substringBefore('?').substringBefore('#') + href
            else URI(currentUrl).resolve(href).toString()
        } catch (error: IllegalArgumentException) {
            throw ParsingException("Invalid PornHub pagination URL", error)
        }
        val currentPage = pageNumber(parameters(URI(currentUrl)))
        if (currentPage == Int.MAX_VALUE) return null
        return Page(if (allowLoadMore) {
            validateChannelContinuation(resolved, currentUrl, currentPage + 1)
        } else validateContinuation(resolved, currentUrl, currentPage + 1))
    }

    /** Read only the route and page count in this known control; never execute its JavaScript. */
    private fun loadMorePage(document: Document, currentUrl: String): Page? {
        val button = document.selectFirst("button#moreDataBtn[onclick]") ?: return null
        if (button.hasAttr("disabled") || button.hasClass("disabled") ||
            button.attr("aria-disabled") == "true"
        ) return null
        val command = Regex(
            "^\\s*(?:return\\s+)?loadMoreData\\(\\s*(['\"])(.*?)\\1\\s*,\\s*" +
                "(?:(['\"])(\\d+)\\3|(\\d+))\\s*(?:,|\\))"
        )
            .find(button.attr("onclick")) ?: return null
        val totalPages = command.groupValues[4].ifEmpty { command.groupValues[5] }.toIntOrNull() ?: return null
        val currentPage = pageNumber(parameters(URI(currentUrl)))
        if (currentPage >= totalPages || currentPage == Int.MAX_VALUE) return null
        val route = try {
            URI(currentUrl).resolve(command.groupValues[2]).toString()
        } catch (cause: IllegalArgumentException) {
            throw ParsingException("Invalid PornHub load-more URL", cause)
        }
        val currentPath = URI(currentUrl).path
        val routeUri = URI(route)
        // A profile's Videos control can name its uploads subset. Keep paginating the
        // selected Videos route, as the upstream extractor does, rather than switching tabs.
        val currentId = PornHubUrls.channelId(currentUrl)
        val routeId = PornHubUrls.channelId(route)
        val uploadControl = (currentPath.startsWith("/model/") || currentPath.startsWith("/pornstar/")) &&
            PornHubUrls.sameChannel(currentId, routeId) &&
            routeUri.path == URI(PornHubUrls.channelVideosUrl(routeId)).path + "/upload"
        val checkedRoute = if (uploadControl) {
            route.substringBefore('?').substringBefore('#').removeSuffix("/upload") +
                routeUri.rawQuery?.let { "?$it" }.orEmpty() + routeUri.rawFragment?.let { "#$it" }.orEmpty()
        } else route
        validateChannelContinuation(checkedRoute, currentUrl, minimumPage = 1)
        val query = (parameters(URI(currentUrl)).filterKeys { it != "page" } +
            ("page" to (currentPage + 1).toString())).entries.joinToString("&") { (key, value) ->
            URLEncoder.encode(key, "UTF-8") + "=" + URLEncoder.encode(value, "UTF-8")
        }
        return Page(validateContinuation(currentUrl.substringBefore('?') + "?" + query, currentUrl, currentPage + 1))
    }

    private fun parameters(uri: URI): Map<String, String> {
        val result = linkedMapOf<String, String>()
        for (component in uri.rawQuery.orEmpty().split('&').filter(String::isNotEmpty)) {
            val pair = component.split('=', limit = 2)
            val key: String
            val value: String
            try {
                key = URLDecoder.decode(pair[0], "UTF-8")
                value = URLDecoder.decode(pair.getOrElse(1) { "" }, "UTF-8")
            } catch (error: IllegalArgumentException) {
                throw ParsingException("Invalid PornHub pagination query", error)
            }
            if (result.put(key, value) != null) throw ParsingException("Ambiguous PornHub pagination query")
        }
        return result
    }

    private fun pageNumber(parameters: Map<String, String>): Int {
        val raw = parameters["page"] ?: return 1
        if (!raw.matches(Regex("[0-9]+"))) throw ParsingException("Invalid PornHub page number")
        return raw.toIntOrNull()?.takeIf { it > 0 }
            ?: throw ParsingException("Invalid PornHub page number")
    }
}
