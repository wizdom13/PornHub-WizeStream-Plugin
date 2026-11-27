/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.wizestream.plugin.pornhub

import org.schabi.newpipe.extractor.exceptions.ParsingException
import org.schabi.newpipe.extractor.linkhandler.ChannelTabs
import org.schabi.newpipe.extractor.linkhandler.ListLinkHandler
import org.schabi.newpipe.extractor.linkhandler.ListLinkHandlerFactory
import org.schabi.newpipe.extractor.search.filter.FilterItem

internal class PornHubChannelLinkHandlerFactory(private val videosTab: Boolean = false) :
    ListLinkHandlerFactory() {
    override fun getId(url: String): String = PornHubUrls.channelId(url)

    override fun onAcceptUrl(url: String): Boolean = try {
        getId(url)
        true
    } catch (_: ParsingException) {
        false
    }

    override fun getUrl(
        id: String,
        contentFilter: List<FilterItem>?,
        sortFilter: List<FilterItem>?
    ): String {
        if (!sortFilter.isNullOrEmpty() || (!contentFilter.isNullOrEmpty() &&
                (!videosTab || contentFilter.size != 1 || contentFilter[0].name != ChannelTabs.VIDEOS))
        ) throw ParsingException("Unsupported PornHub channel filter")
        return if (videosTab) PornHubUrls.channelVideosUrl(id) else PornHubUrls.channelUrl(id)
    }

    override fun fromUrl(url: String): ListLinkHandler = handler(getId(url), url)

    override fun fromUrl(url: String, baseUrl: String): ListLinkHandler = fromUrl(url)

    override fun fromId(id: String): ListLinkHandler = handler(id)

    override fun fromId(id: String, baseUrl: String): ListLinkHandler = fromId(id)

    private fun handler(id: String, originalUrl: String? = null): ListLinkHandler {
        val filters = if (videosTab) listOf(FilterItem(-1, ChannelTabs.VIDEOS)) else emptyList()
        val url = getUrl(id, filters, null)
        return ListLinkHandler(originalUrl ?: url, url, id, filters, emptyList())
    }
}
