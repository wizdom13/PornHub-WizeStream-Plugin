/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.wizestream.plugin.pornhub

import com.grack.nanojson.JsonArray
import com.grack.nanojson.JsonParser
import java.io.IOException
import java.net.URI
import java.util.Locale
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.exceptions.AgeRestrictedContentException
import org.schabi.newpipe.extractor.exceptions.ContentNotAvailableException
import org.schabi.newpipe.extractor.exceptions.GeographicRestrictionException
import org.schabi.newpipe.extractor.exceptions.ParsingException
import org.schabi.newpipe.extractor.exceptions.PrivateContentException
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException

/** All extraction requests use the host downloader; no browser code or credentials are executed. */
internal class PornHubClient(
    downloader: Downloader,
    timeoutMillis: Long = PornHubRequests.DEFAULT_TIMEOUT_MILLIS
) {
    private val requests = PornHubRequests(downloader, timeoutMillis)

    fun getPage(url: String): Document {
        val requestUrl = PornHubUrls.siteUrl(url)
        val response = requests.get(requestUrl, headers(PornHubUrls.BASE_URL + "/"))
        val body = boundedBody(response)
        val finalUrl = response.latestUrl().ifBlank { requestUrl }
        PornHubUrls.siteUrl(finalUrl)
        val document = Jsoup.parse(body, finalUrl)
        checkPageAvailability(document)
        checkStatus(response)
        return document
    }

    /** A get_media response is an array of sources, never a video stream itself. */
    fun getJsonArray(url: String, referer: String): JsonArray {
        val requestUrl = PornHubUrls.siteUrl(url)
        val path = URI(requestUrl).path
        if (path != "/video/get_media" && path != "/video/get_media.php") {
            throw ParsingException("Unsupported PornHub media metadata endpoint")
        }
        val response = requests.get(requestUrl, headers(PornHubUrls.siteUrl(referer)))
        val body = boundedBody(response)
        val finalUrl = response.latestUrl().ifBlank { requestUrl }
        PornHubUrls.siteUrl(finalUrl)
        if (URI(finalUrl).path != path) throw ContentNotAvailableException("Media metadata was redirected")
        checkStatus(response)
        return try {
            JsonParser.array().from(body)
        } catch (cause: Exception) {
            throw ParsingException("PornHub returned invalid media metadata", cause)
        }
    }

    private fun headers(referer: String): Map<String, List<String>> = mapOf(
        "Referer" to listOf(referer),
        "Origin" to listOf(PornHubUrls.BASE_URL),
        "Accept-Language" to listOf("en-US,en;q=0.8")
    )

    private fun boundedBody(response: Response): String = response.responseBody().also {
        if (it.length > 8 * 1024 * 1024) throw ParsingException("PornHub response exceeds the parser limit")
    }

    private fun checkStatus(response: Response) {
        when (response.responseCode()) {
            in 200..299 -> Unit
            401 -> throw PrivateContentException("PornHub requires authentication")
            403 -> throw ContentNotAvailableException("PornHub denied this request; browser verification may be required")
            404, 410 -> throw ContentNotAvailableException("This PornHub resource is unavailable")
            451 -> throw GeographicRestrictionException("PornHub is unavailable in this region")
            429 -> throw ContentNotAvailableException("PornHub rate limit reached; try again later")
            else -> throw IOException("PornHub returned HTTP " + response.responseCode())
        }
    }

    private fun checkPageAvailability(document: Document) {
        val path = URI(document.location()).path.lowercase(Locale.ROOT)
        fun hidden(element: Element): Boolean = element.hasAttr("hidden") || element.hasClass("hidden") ||
            Regex("(?:^|;)\\s*(?:display\\s*:\\s*none|visibility\\s*:\\s*hidden)\\s*(?:!important)?\\s*(?:;|$)",
                RegexOption.IGNORE_CASE).containsMatchIn(element.attr("style"))
        val visibleDocument = document.clone()
        visibleDocument.allElements.filter(::hidden).forEach(Element::remove)
        val pageText = visibleDocument.body().text().lowercase(Locale.ROOT)
        val hasPublicStructure = document.getElementById("videoSearchResult") != null ||
            document.getElementById("videoCategory") != null ||
            PornHubChannelMetadata.hasProfileStructure(document) ||
            document.select("script").any { Regex("\\bflashvars_\\d+\\s*=").containsMatchIn(it.data()) }
        fun visible(selector: String): Boolean = document.select(selector).any { element ->
            !hidden(element) && element.parents().none(::hidden)
        }
        if (visible(".geoBlocked, #geoBlocked") ||
            (!hasPublicStructure && (pageText.contains("not available in your country") ||
                pageText.contains("not available in your region")))
        ) throw GeographicRestrictionException("This PornHub resource is unavailable in your region")
        if (path.contains("age-verification") || path.contains("age_verification") ||
            visible("#age-verification, #ageVerification, form[action*=age-verification]") ||
            (!hasPublicStructure && pageText.contains("age verification required"))
        ) throw AgeRestrictedContentException("PornHub requires age verification in its website")
        if (path == "/login" || path.startsWith("/login/") || visible("#lockedPlayer")) {
            throw PrivateContentException("This PornHub resource requires login or is private")
        }
        if (visible(".removed, section.noVideo") ||
            visibleDocument.select(".userMessageSection").any { it.text().contains("unavailable", ignoreCase = true) }
        ) throw ContentNotAvailableException("This PornHub video has been removed or is unavailable")
        if (visible("#challenge-form, #cf-challenge-running") ||
            (!hasPublicStructure && visible(".g-recaptcha")) ||
            document.body().attr("onload").contains("go()") ||
            document.select("script").any {
                val data = it.data()
                data.contains("document.location.reload(true)") && data.contains("RNKEY")
            }
        ) throw ReCaptchaException("PornHub requires browser verification", document.location())
    }
}
