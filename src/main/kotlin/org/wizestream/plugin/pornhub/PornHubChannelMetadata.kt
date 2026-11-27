/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.wizestream.plugin.pornhub

import java.net.URI
import org.jsoup.nodes.Document
import org.schabi.newpipe.extractor.Image
import org.schabi.newpipe.extractor.exceptions.ParsingException

/** Optional profile metadata is independent of the dedicated video-list request. */
internal data class PornHubChannelMetadata(
    val channelId: String,
    val name: String,
    val description: String,
    val avatars: List<Image>,
    val banners: List<Image>
) {
    companion object {
        fun parse(document: Document, channelId: String): PornHubChannelMetadata {
            val profileUrl = PornHubUrls.channelUrl(channelId)
            val finalUrl = document.location().ifBlank { profileUrl }
            var resolvedId = PornHubUrls.channelId(finalUrl)
            if (!PornHubUrls.sameChannel(channelId, resolvedId) ||
                URI(finalUrl).path.trimEnd('/') != URI(PornHubUrls.channelUrl(resolvedId)).path
            ) throw ParsingException("PornHub redirected to a different channel page")
            document.selectFirst("link[rel=canonical][href]")?.absUrl("href")?.takeIf(String::isNotBlank)?.let {
                val canonicalId = PornHubUrls.channelId(it)
                if (!PornHubUrls.sameChannel(channelId, canonicalId)) {
                    throw ParsingException("PornHub returned metadata for a different channel")
                }
                resolvedId = canonicalId
            }
            if (!hasProfileStructure(document)) {
                throw ParsingException("PornHub returned an unrecognized channel profile")
            }

            val name = sequenceOf(
                "h1[itemprop=name]", ".name h1", "div.profileUserName a", "div.profileUserName",
                "#topProfileHeader h1", ".topProfileHeader h1", ".titleWrapper h1", ".coverImage h1"
            ).mapNotNull { selector -> document.selectFirst(selector)?.clone()?.apply {
                select(".userBadges, .bg-channel-badge, .verified-icon, .verifiedIcon, .verifiedPornstar, .trophyChannel").remove()
            }?.text()?.trim()?.takeIf(String::isNotBlank) }.firstOrNull()
                ?: document.selectFirst("meta[property=og:title], meta[name=twitter:title]")
                    ?.attr("content")?.trim()?.takeIf(String::isNotBlank)
                    ?.replace(Regex("\\s*[-|]\\s*Pornhub(?:\\.(?:com|org))?\\s*$", RegexOption.IGNORE_CASE), "")
                    ?.trim()?.takeIf(String::isNotBlank)
                ?: channelId.substringAfter('/')
            val description = document.selectFirst(
                "div.biographyAbout div[itemprop=description], div[itemprop=description], p.aboutMeText"
            )?.text()?.trim()?.takeIf(String::isNotBlank)
                ?: document.select("div.cdescriptions p")
                    .firstOrNull { it.selectFirst(".channelInfoHeadlines") == null }
                    ?.text()?.trim().orEmpty()
            return PornHubChannelMetadata(
                resolvedId,
                name,
                description,
                images(document, listOf(
                    "img#getAvatar", "#avatarPicture img", ".profilePicBorder img", "img.avatarTrigger",
                    ".previewAvatarPicture img", "#topProfileHeader .avatar img", "#channelsProfile .avatar img"
                ), metadataFallback = true),
                images(document, listOf("img#coverPictureDefault", "#coverPicture img", ".coverImage img", ".cover img"))
            )
        }

        fun hasProfileStructure(document: Document): Boolean = document.selectFirst(
            "#channelsProfile, #topProfileHeader, .topProfileHeader, div.profileUserName, " +
                "h1[itemprop=name], .titleWrapper h1, .name h1, .coverImage h1, " +
                "div.profileVids, #mostRecentVideosSection, #showAllChanelVideos"
        ) != null

        private fun images(document: Document, selectors: List<String>, metadataFallback: Boolean = false): List<Image> {
            fun image(url: String) = listOf(
                Image(url, Image.HEIGHT_UNKNOWN, Image.WIDTH_UNKNOWN, Image.ResolutionLevel.UNKNOWN)
            )
            for (selector in selectors) {
                val element = document.selectFirst(selector) ?: continue
                for (attribute in listOf("data-src", "src")) {
                    val url = PornHubUrls.mediaUrl(element.absUrl(attribute)) ?: continue
                    return image(url)
                }
            }
            if (metadataFallback) {
                for (element in document.select("meta[property=og:image], meta[name=twitter:image]")) {
                    val url = PornHubUrls.mediaUrl(element.absUrl("content")) ?: continue
                    return image(url)
                }
            }
            return emptyList()
        }
    }
}
