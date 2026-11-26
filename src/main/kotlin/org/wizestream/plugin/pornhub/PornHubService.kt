/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.wizestream.plugin.pornhub

import java.net.URI
import org.schabi.newpipe.extractor.StreamingService
import org.schabi.newpipe.extractor.channel.ChannelExtractor
import org.schabi.newpipe.extractor.channel.ChannelTabExtractor
import org.schabi.newpipe.extractor.comments.CommentsExtractor
import org.schabi.newpipe.extractor.exceptions.ExtractionException
import org.schabi.newpipe.extractor.exceptions.ParsingException
import org.schabi.newpipe.extractor.kiosk.KioskList
import org.schabi.newpipe.extractor.linkhandler.LinkHandler
import org.schabi.newpipe.extractor.linkhandler.LinkHandlerFactory
import org.schabi.newpipe.extractor.linkhandler.ListLinkHandler
import org.schabi.newpipe.extractor.linkhandler.ListLinkHandlerFactory
import org.schabi.newpipe.extractor.linkhandler.SearchQueryHandler
import org.schabi.newpipe.extractor.linkhandler.SearchQueryHandlerFactory
import org.schabi.newpipe.extractor.playlist.PlaylistExtractor
import org.schabi.newpipe.extractor.search.SearchExtractor
import org.schabi.newpipe.extractor.search.filter.FilterItem
import org.schabi.newpipe.extractor.stream.StreamExtractor
import org.schabi.newpipe.extractor.subscription.SubscriptionExtractor
import org.schabi.newpipe.extractor.suggestion.SuggestionExtractor

/** The host assigns the ID; keeping it unchanged preserves saved items across plugin updates. */
class PornHubService(id: Int) : StreamingService(
    id,
    "PornHub",
    listOf(ServiceInfo.MediaCapability.VIDEO)
) {
    override fun getBaseUrl(): String = PornHubUrls.BASE_URL

    // The service publishes expiring media URLs. Avoid the host's one-hour default cache.
    override fun getInfoCacheExpirationMillis(): Long = 5 * 60 * 1000L

    override fun getImageRequestHeaders(imageUrl: String): Map<String, String>? {
        val valid = PornHubUrls.mediaUrl(imageUrl) ?: return null
        val host = URI(valid).host.lowercase()
        if (listOf("phncdn.com", "pornhub.com", "pornhub.org", "pornhub.net")
                .none { host == it || host.endsWith(".$it") }) {
            return null
        }
        return mapOf("Origin" to PornHubUrls.BASE_URL, "Referer" to "${PornHubUrls.BASE_URL}/")
    }

    override fun getStreamLHFactory(): LinkHandlerFactory = object : LinkHandlerFactory() {
        override fun getId(url: String): String = PornHubUrls.streamId(url)
        override fun getUrl(id: String): String = PornHubUrls.streamUrl(id)
        override fun onAcceptUrl(url: String): Boolean = try {
            getId(url)
            true
        } catch (_: ParsingException) {
            false
        }
    }

    override fun getSearchQHFactory(): SearchQueryHandlerFactory = object : SearchQueryHandlerFactory() {
        override fun getUrl(
            query: String,
            contentFilter: List<FilterItem>?,
            sortFilter: List<FilterItem>?
        ): String = PornHubUrls.searchUrl(query)
    }

    override fun getSearchExtractor(queryHandler: SearchQueryHandler): SearchExtractor =
        PornHubSearchExtractor(this, queryHandler)

    override fun getStreamExtractor(linkHandler: LinkHandler): StreamExtractor =
        PornHubStreamExtractor(this, linkHandler)

    override fun getKioskList(): KioskList = KioskList(this).apply {
        val handler = VideosLinkHandlerFactory()
        addKioskEntry(
            { _, url, id -> PornHubKioskExtractor(this@PornHubService, handler.fromUrl(url), id) },
            handler,
            VIDEOS_KIOSK
        )
        setDefaultKiosk(VIDEOS_KIOSK)
    }

    override fun getChannelLHFactory(): ListLinkHandlerFactory? = null
    override fun getChannelTabLHFactory(): ListLinkHandlerFactory? = null
    override fun getPlaylistLHFactory(): ListLinkHandlerFactory? = null
    override fun getCommentsLHFactory(): ListLinkHandlerFactory? = null
    override fun getSuggestionExtractor(): SuggestionExtractor? = null
    override fun getSubscriptionExtractor(): SubscriptionExtractor? = null

    override fun getChannelExtractor(linkHandler: ListLinkHandler): ChannelExtractor =
        throw ExtractionException("PornHub channel browsing is not supported by this plugin")

    override fun getChannelTabExtractor(linkHandler: ListLinkHandler): ChannelTabExtractor =
        throw ExtractionException("PornHub channel tabs are not supported by this plugin")

    override fun getPlaylistExtractor(linkHandler: ListLinkHandler): PlaylistExtractor =
        throw ExtractionException("PornHub playlists are not supported by this plugin")

    override fun getCommentsExtractor(linkHandler: ListLinkHandler): CommentsExtractor =
        throw ExtractionException("PornHub comments are not supported by this plugin")

    companion object {
        const val VIDEOS_KIOSK = "Videos"
    }
}

private class VideosLinkHandlerFactory : ListLinkHandlerFactory() {
    override fun getId(url: String): String {
        PornHubCatalog.validateContinuation(url, PornHubUrls.listingUrl(), minimumPage = 1)
        return PornHubService.VIDEOS_KIOSK
    }

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
        if (id != PornHubService.VIDEOS_KIOSK) throw ParsingException("Unknown PornHub kiosk")
        return PornHubUrls.listingUrl()
    }
}
