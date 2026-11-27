/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.wizestream.plugin.pornhub

import org.schabi.newpipe.extractor.Image
import org.schabi.newpipe.extractor.channel.ChannelExtractor
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.linkhandler.ListLinkHandler

class PornHubChannelExtractor(
    private val pluginService: PornHubService,
    handler: ListLinkHandler
) : ChannelExtractor(pluginService, handler) {
    private val channelId = handler.id
    private val profileUrl = PornHubUrls.channelUrl(channelId)
    private lateinit var profile: PornHubChannelMetadata

    override fun onFetchPage(downloader: Downloader) {
        profile = PornHubChannelMetadata.parse(PornHubClient(downloader).getPage(profileUrl), channelId)
    }

    override fun getId(): String = if (::profile.isInitialized) profile.channelId else channelId

    override fun getUrl(): String = PornHubUrls.channelUrl(id)

    override fun getName(): String = profile.name

    override fun getDescription(): String = profile.description

    override fun getAvatars(): List<Image> = profile.avatars

    override fun getBanners(): List<Image> = profile.banners

    override fun getSubscriberCount(): Long = UNKNOWN_SUBSCRIBER_COUNT

    // Both the channel screen and subscription feeds load this tab independently.
    // Leave the legacy initial page empty to avoid fetching the same list twice.
    override fun getTabs(): List<ListLinkHandler> = listOf(
        requireNotNull(pluginService.channelTabLHFactory).fromId(id)
    )
}
