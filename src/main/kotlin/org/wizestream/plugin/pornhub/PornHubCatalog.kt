/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.wizestream.plugin.pornhub

import java.net.URI
import java.net.URLDecoder
import org.jsoup.nodes.Document
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
        val finalUrl = validateContinuation(document.location().ifBlank { pageUrl }, pageUrl, minimumPage = 1)
        if (pageNumber(parameters(URI(finalUrl))) != pageNumber(parameters(URI(pageUrl)))) {
            throw ParsingException("PornHub redirected the requested listing page")
        }
        val container = document.getElementById(containerId)
        if (container == null) {
            if (document.selectFirst(".noResults, .no-results, .no_results, #noResults") != null) {
                return InfoItemsPage.emptyPage()
            }
            throw ParsingException("PornHub returned an unrecognized video listing")
        }

        val collector = StreamInfoItemsCollector(serviceId)
        val ids = mutableSetOf<String>()
        for (card in container.select("li.videoblock")) {
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
        return InfoItemsPage(collector, nextPage(document, pageUrl))
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
        if (next.host != initial.host || next.path != initial.path || next.fragment != null ||
            nextQuery.filterKeys { it != "page" } != initialQuery.filterKeys { it != "page" } ||
            page < minimumPage) {
            throw ParsingException("PornHub pagination changed the listing or did not advance")
        }
        return valid
    }

    private fun nextPage(document: Document, currentUrl: String): Page? {
        val marker = document.selectFirst("li.page_next")
        if (marker?.hasClass("disabled") == true || marker?.attr("aria-disabled") == "true") return null
        val link = marker?.selectFirst("a[href]")
            ?: document.selectFirst("a[rel=next][href], link[rel=next][href]")
            ?: return null
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
        return Page(validateContinuation(resolved, currentUrl, currentPage + 1))
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
