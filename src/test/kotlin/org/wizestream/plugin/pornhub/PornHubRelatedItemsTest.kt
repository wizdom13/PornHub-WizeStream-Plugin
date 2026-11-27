/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.wizestream.plugin.pornhub

import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PornHubRelatedItemsTest {
    @Test
    fun desktopRecommendationsKeepMetadataAndIgnoreOtherPageLists() {
        val result = parse("""
            <ul id=videoCategory>${card("phoutside001", "Unrelated catalog fixture")}</ul>
            <ul id=relatedVideosCenter>
              <li class=videoblock>
                <a class=linkVideoThumb href='/view_video.php?viewkey=phrelated001' title='Related fixture'></a>
                <img data-thumb_url='https://cdn.example.com/related.jpg' alt='Related fixture'>
                <span class=duration>2:05</span><span class=views><var>12.3K</var></span>
                <span class=usernameWrap><a href='/model/fixture-uploader'>Fixture uploader</a></span>
              </li>
            </ul>
        """)
        val item = result.items.single()
        assertEquals(1200, item.serviceId)
        assertEquals("Related fixture", item.name)
        assertEquals("https://www.pornhub.com/view_video.php?viewkey=phrelated001", item.url)
        assertEquals(125L, item.duration)
        assertEquals(12300L, item.viewCount)
        assertEquals("Fixture uploader", item.uploaderName)
        assertEquals("https://www.pornhub.com/model/fixture-uploader", item.uploaderUrl)
        assertEquals("https://cdn.example.com/related.jpg", item.thumbnailUrl)
        assertTrue(result.errors.isEmpty())
    }

    @Test
    fun desktopMobileAndSidebarSectionsShareCanonicalDeduplication() {
        val result = parse("""
            <div class=relatedVideos><ul id=relatedVideos>
              ${card("phcurrent001", "Current fixture")}
              ${card("phrelated001", "First related fixture")}
            </ul></div>
            <ul id=relateRecommendedItems>
              <li><a class=linkVideoThumb href='https://de.pornhub.org/video/show?viewkey=phrelated001&amp;from=related' title='Duplicate'></a></li>
              ${card("phrelated002", "Second related fixture")}
            </ul>
            <div id=recommendedVideosVPage><ul>
              ${card("phrelated002", "Repeated sidebar fixture")}
              ${card("phrelated003", "Third related fixture")}
            </ul></div>
        """)
        assertEquals(listOf("First related fixture", "Second related fixture", "Third related fixture"), result.items.map { it.name })
        assertEquals(listOf("phrelated001", "phrelated002", "phrelated003"), result.items.map { PornHubUrls.streamId(it.url) })
        assertTrue(result.errors.isEmpty())
    }

    @Test
    fun advertisementsPremiumCardsAndUnsupportedLinksAreExcluded() {
        val result = parse("""
            <ul id=relatedVideosCenter>
              ${card("phadvert001", "Advertisement", "class='videoblock sponsored'")}
              ${card("phadvert002", "Data advertisement", "data-is-ad='1'")}
              <li><a class=linkVideoThumb href='/view_video.php?viewkey=phpremium01' title='Premium fixture'><i class=premiumIcon></i></a></li>
              <li><a class=linkVideoThumb href='/view_video.php?viewkey=phpremium02' title='Premium fixture'><i class=ph-icon-badge-premium></i></a></li>
              <li><a class=linkVideoThumb href='https://www.pornhubpremium.com/view_video.php?viewkey=phpremium03' title='Premium gateway'></a></li>
              <li><a class=linkVideoThumb href='https://unrelated.example.com/view_video.php?viewkey=phother001' title='External fixture'></a></li>
              <li><a class=linkVideoThumb href='javascript:void(0)' title='Placeholder'></a></li>
              <li><a class=linkVideoThumb href='/view_video.php?viewkey=first&amp;viewkey=second' title='Ambiguous fixture'></a></li>
              ${card("phrelated001", "Public fixture")}
            </ul>
            <aside class=adContainer><ul id=relatedVideos>
              ${card("phadvert003", "Advertisement section")}
            </ul></aside>
        """)
        assertEquals(listOf("Public fixture"), result.items.map { it.name })
        assertTrue(result.errors.isEmpty())
    }

    @Test
    fun malformedCardsDoNotDiscardValidRecommendationsOrSuppressValidCopies() {
        val result = parse("""
            <div class=relatedVideos><ul id=relatedVideosCenter>
              <li><a class=linkVideoThumb href='/view_video.php?viewkey=phrelated001'></a></li>
              ${card("phrelated001", "Recovered fixture")}
              ${card("phrelated002", "Other fixture")}
            </ul></div>
        """)
        assertEquals(listOf("Recovered fixture", "Other fixture"), result.items.map { it.name })
        assertEquals(1, result.errors.size)
    }

    @Test
    fun nestedCardMenusDoNotBecomeRecommendationsOrDuplicateErrors() {
        val result = parse("""
            <div class=relatedVideos><ul id=relatedVideosCenter><li>
              <a class=linkVideoThumb href='/view_video.php?viewkey=phrelated001' title='Related fixture'></a>
              <ul><li><a href='/view_video.php?viewkey=phmenu001'>Nested menu fixture</a></li></ul>
            </li></ul></div>
        """)
        assertEquals(listOf("Related fixture"), result.items.map { it.name })
        assertTrue(result.errors.isEmpty())
    }

    @Test
    fun missingOrEmptyRecommendationSectionsRemainEmpty() {
        for (html in listOf(
            "<main>Fixture without recommendations</main>",
            "<ul id=relatedVideosCenter></ul>",
            "<ul id=videoSearchResult>${card("phoutside001", "Search fixture")}</ul>"
        )) {
            val result = parse(html)
            assertTrue(result.items.isEmpty())
            assertTrue(result.errors.isEmpty())
        }
    }

    private fun parse(html: String) = PornHubRelatedItems.parse(
        Jsoup.parse(html, "https://www.pornhub.com/view_video.php?viewkey=phcurrent001"),
        1200,
        "phcurrent001"
    )

    private fun card(id: String, title: String, attributes: String = "class=videoblock") =
        "<li $attributes><a class=linkVideoThumb href='/view_video.php?viewkey=$id' title='$title'></a></li>"
}
