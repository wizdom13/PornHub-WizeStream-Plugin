/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.wizestream.plugin.pornhub

import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.schabi.newpipe.extractor.exceptions.ParsingException
import org.schabi.newpipe.extractor.stream.StreamInfoItemsCollector

/** Recommendations embedded in the fetched watch page, without a second network request. */
internal object PornHubRelatedItems {
    private const val SECTIONS = "#relatedVideosCenter, #relatedVideos, #relateRecommendedItems, " +
        "#recommendedVideosVPage, .relatedVideos"

    fun parse(document: Document, serviceId: Int, currentVideoId: String): StreamInfoItemsCollector {
        val collector = StreamInfoItemsCollector(serviceId)
        val seen = mutableSetOf(currentVideoId)
        val cards = linkedSetOf<Element>()
        for (section in document.select(SECTIONS)) {
            // Nested section wrappers can describe the same list. Process each outer card once,
            // rather than treating its nested menu/list items as additional recommendations.
            cards += section.select("li").filter { card ->
                card.parents().takeWhile { it != section }.none { it.tagName() == "li" }
            }
        }
        for (card in cards) {
            val item = PornHubStreamItemExtractor(card)
            if (item.isAd() || card.parents().any { it.hasClass("ad") || it.hasClass("adContainer") || it.hasClass("sponsored") } ||
                card.selectFirst(".premiumIcon, .ph-icon-badge-premium") != null
            ) continue
            val id = try {
                PornHubUrls.streamId(item.getUrl())
            } catch (_: ParsingException) {
                // Premium gateways, ads, and placeholders do not identify public video cards.
                continue
            }
            if (id in seen) continue
            val previousCount = collector.items.size
            collector.commit(item)
            // A malformed copy must not suppress a usable copy later in the page.
            if (collector.items.size > previousCount) seen.add(id)
        }
        return collector
    }
}
