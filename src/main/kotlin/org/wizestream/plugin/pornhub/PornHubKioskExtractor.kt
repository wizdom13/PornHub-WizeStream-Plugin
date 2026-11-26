/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.wizestream.plugin.pornhub

import org.jsoup.nodes.Document
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.Page
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.kiosk.KioskExtractor
import org.schabi.newpipe.extractor.linkhandler.ListLinkHandler
import org.schabi.newpipe.extractor.stream.StreamInfoItem

class PornHubKioskExtractor(
    service: PornHubService,
    handler: ListLinkHandler,
    kioskId: String
) : KioskExtractor<StreamInfoItem>(service, handler, kioskId) {
    private lateinit var document: Document

    override fun getName(): String = PornHubService.VIDEOS_KIOSK

    override fun onFetchPage(downloader: Downloader) {
        document = PornHubClient(downloader).getPage(url)
    }

    override fun getInitialPage(): InfoItemsPage<StreamInfoItem> {
        assertPageFetched()
        return PornHubCatalog.parse(document, url, serviceId, "videoCategory")
    }

    override fun getPage(page: Page): InfoItemsPage<StreamInfoItem> {
        val nextUrl = PornHubCatalog.validateContinuation(page.url, url)
        val nextDocument = PornHubClient(NewPipe.getDownloader()).getPage(nextUrl)
        return PornHubCatalog.parse(nextDocument, nextUrl, serviceId, "videoCategory")
    }
}
