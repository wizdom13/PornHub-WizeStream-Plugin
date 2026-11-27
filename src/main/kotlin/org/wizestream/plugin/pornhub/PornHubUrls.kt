/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.wizestream.plugin.pornhub

import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.Locale
import org.schabi.newpipe.extractor.exceptions.ParsingException

/** URL parsing is shared by navigation, pagination and network boundaries. */
internal object PornHubUrls {
    const val BASE_URL = "https://www.pornhub.com"
    private val videoId = Regex("[A-Za-z0-9]{1,64}")
    private val siteHost = Regex("(?:(?:www|m|[a-z]{2}(?:-[a-z]{2})?)\\.)?pornhub\\.(?:com|org)")
    private val channelIdentity = Regex("(channels|model|pornstar|users)/([A-Za-z0-9][A-Za-z0-9_.-]{0,199})")
    private val channelPath = Regex(
        "/(channels|model|pornstar|users)/([A-Za-z0-9][A-Za-z0-9_.-]{0,199})(?:/(videos(?:/(?:upload|public))?))?/?"
    )

    fun streamId(url: String): String {
        val uri = parse(url)
        requireSite(uri, allowHttp = true)
        val id = when (uri.rawPath) {
            "/view_video.php", "/video/show" -> parameters(uri).filter { it.first == "viewkey" }
                .singleOrNull()?.second
            else -> Regex("/embed/([A-Za-z0-9]{1,64})/?")
                .matchEntire(uri.rawPath.orEmpty())?.groupValues?.get(1)
        }
        return id?.takeIf(videoId::matches)
            ?: throw ParsingException("Not a supported PornHub video URL")
    }

    fun streamUrl(id: String): String {
        if (!videoId.matches(id)) throw ParsingException("Invalid PornHub video identifier")
        return "$BASE_URL/view_video.php?viewkey=$id"
    }

    /** Profile roots and public video tabs share one stable channel identity. */
    fun channelId(url: String): String {
        val uri = parse(url)
        requireSite(uri, allowHttp = true)
        val match = channelPath.matchEntire(uri.rawPath.orEmpty())
            ?: throw ParsingException("Not a supported PornHub channel URL")
        val kind = match.groupValues[1]
        val tab = match.groupValues[3]
        if ((tab == "videos/public" && kind != "users") ||
            (tab == "videos/upload" && kind == "channels")
        ) throw ParsingException("Not a supported PornHub channel video tab")
        return "$kind/${match.groupValues[2]}"
    }

    fun channelUrl(id: String): String {
        if (!channelIdentity.matches(id)) throw ParsingException("Invalid PornHub channel identifier")
        return "$BASE_URL/$id"
    }

    fun channelVideosUrl(id: String): String = channelUrl(id) +
        if (id.startsWith("users/")) "/videos/public" else "/videos"

    /** The site can move a public profile between these two kinds without changing its slug. */
    fun sameChannel(expectedId: String, actualId: String): Boolean {
        if (!channelIdentity.matches(expectedId) || !channelIdentity.matches(actualId)) return false
        if (expectedId == actualId) return true
        return expectedId.substringAfter('/') == actualId.substringAfter('/') &&
            expectedId.substringBefore('/') in setOf("model", "pornstar") &&
            actualId.substringBefore('/') in setOf("model", "pornstar")
    }

    /** An uploader name alone is not a channel URL; only use a supported published link. */
    fun channelUrlOrNull(value: String?, baseUrl: String): String? {
        if (value.isNullOrBlank()) return null
        return try {
            val reference = parse(value.trim())
            if (reference.rawPath.isNullOrEmpty() && reference.host == null) return null
            val resolved = parse(baseUrl).resolve(reference).toString()
            channelUrl(channelId(resolved))
        } catch (_: ParsingException) {
            null
        }
    }

    fun searchUrl(query: String, page: Int = 1): String {
        require(page > 0) { "Page must be positive" }
        return "$BASE_URL/video/search?search=" + URLEncoder.encode(query, "UTF-8") + "&page=$page"
    }

    fun listingUrl(page: Int = 1): String {
        require(page > 0) { "Page must be positive" }
        return "$BASE_URL/video?page=$page"
    }

    /** Keep signed queries intact; normalize only an optional fragment. */
    fun siteUrl(url: String): String {
        requireSite(parse(url), allowHttp = false)
        return url.substringBefore('#')
    }

    fun mediaUrl(value: String?): String? {
        if (value.isNullOrBlank()) return null
        val url = if (value.startsWith("//")) "https:$value" else value
        val uri = try { parse(url) } catch (_: ParsingException) { return null }
        val host = uri.host?.lowercase(Locale.ROOT) ?: return null
        if (uri.scheme != "https" || uri.userInfo != null || uri.port !in setOf(-1, 443)) return null
        // Media is supplied by provider/CDN hostnames. Never accept local files or IP literals.
        if (!host.contains('.') || host.endsWith('.') || host.contains(':') ||
            !Regex("[a-z0-9.-]+").matches(host) || host.substringAfterLast('.').all(Char::isDigit) ||
            listOf("localhost", "local", "internal", "test", "invalid").any { host == it || host.endsWith(".$it") }
        ) return null
        return url.substringBefore('#')
    }

    fun parameters(uri: URI): List<Pair<String, String>> = try {
        uri.rawQuery.orEmpty().split('&').filter(String::isNotEmpty).map { component ->
            URLDecoder.decode(component.substringBefore('='), "UTF-8") to
                URLDecoder.decode(component.substringAfter('=', ""), "UTF-8")
        }
    } catch (cause: IllegalArgumentException) {
        throw ParsingException("Malformed PornHub query", cause)
    }

    private fun parse(value: String): URI = try {
        if (value.any { it <= ' ' || it == '\u007f' || it == '\\' }) {
            throw IllegalArgumentException("Invalid URL characters")
        }
        URI(value)
    } catch (cause: Exception) {
        throw ParsingException("Malformed URL", cause)
    }

    private fun requireSite(uri: URI, allowHttp: Boolean) {
        val scheme = uri.scheme?.lowercase(Locale.ROOT)
        val host = uri.host?.lowercase(Locale.ROOT).orEmpty()
        val validPort = uri.port == -1 || (scheme == "https" && uri.port == 443) ||
            (allowHttp && scheme == "http" && uri.port == 80)
        if ((scheme != "https" && !(allowHttp && scheme == "http")) ||
            !siteHost.matches(host) || uri.userInfo != null || !validPort
        ) throw ParsingException("URL must belong to a supported PornHub host")
    }
}
