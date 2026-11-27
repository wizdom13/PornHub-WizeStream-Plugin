/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.wizestream.plugin.pornhub

import org.jsoup.nodes.Document
import org.schabi.newpipe.extractor.InfoItem
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.Page
import org.schabi.newpipe.extractor.channel.ChannelTabExtractor
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.exceptions.ParsingException
import org.schabi.newpipe.extractor.linkhandler.ChannelTabs
import org.schabi.newpipe.extractor.linkhandler.ListLinkHandler
import org.schabi.newpipe.extractor.stream.StreamInfoItem

class PornHubChannelTabExtractor(
    service: PornHubService,
    handler: ListLinkHandler
) : ChannelTabExtractor(service, handler) {
    private val channelId = handler.id
    private val listingUrl = PornHubUrls.channelVideosUrl(channelId)
    private lateinit var document: Document

    init {
        if (handler.contentFilters.size != 1 || handler.contentFilters.single().name != ChannelTabs.VIDEOS ||
            !handler.sortFilter.isNullOrEmpty()
        ) throw ParsingException("PornHub only provides the public Videos channel tab")
    }

    override fun onFetchPage(downloader: Downloader) {
        document = PornHubClient(downloader).getPage(listingUrl)
    }

    override fun getInitialPage(): InfoItemsPage<InfoItem> {
        assertPageFetched()
        return convert(PornHubCatalog.parseChannel(document, listingUrl, serviceId, channelId))
    }

    override fun getPage(page: Page): InfoItemsPage<InfoItem> {
        val nextUrl = PornHubCatalog.validateChannelContinuation(page.url, listingUrl)
        val nextDocument = PornHubClient(NewPipe.getDownloader()).getPage(nextUrl)
        return convert(PornHubCatalog.parseChannel(nextDocument, nextUrl, serviceId, channelId))
    }

    private fun convert(page: InfoItemsPage<StreamInfoItem>): InfoItemsPage<InfoItem> =
        InfoItemsPage(page.items.map { it as InfoItem }, page.nextPage, page.errors)
}
