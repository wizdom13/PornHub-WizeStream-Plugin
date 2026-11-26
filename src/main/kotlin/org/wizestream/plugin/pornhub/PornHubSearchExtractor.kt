/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.wizestream.plugin.pornhub

import org.jsoup.nodes.Document
import org.schabi.newpipe.extractor.InfoItem
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.Page
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.linkhandler.SearchQueryHandler
import org.schabi.newpipe.extractor.search.SearchExtractor

class PornHubSearchExtractor(service: PornHubService, queryHandler: SearchQueryHandler) :
    SearchExtractor(service, queryHandler) {
    private lateinit var document: Document

    override fun onFetchPage(downloader: Downloader) {
        document = PornHubClient(downloader).getPage(url)
    }

    override fun getInitialPageInternal(): InfoItemsPage<InfoItem> {
        assertPageFetched()
        return collect(document, url)
    }

    override fun getPageInternal(page: Page): InfoItemsPage<InfoItem> {
        val nextUrl = PornHubCatalog.validateContinuation(page.url, url)
        return collect(PornHubClient(NewPipe.getDownloader()).getPage(nextUrl), nextUrl)
    }

    private fun collect(document: Document, pageUrl: String): InfoItemsPage<InfoItem> {
        val result = PornHubCatalog.parse(document, pageUrl, serviceId, "videoSearchResult")
        return InfoItemsPage(result.items.map { it as InfoItem }, result.nextPage, result.errors)
    }
}
