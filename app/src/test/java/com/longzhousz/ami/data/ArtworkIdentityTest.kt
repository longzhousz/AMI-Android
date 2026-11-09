package com.longzhousz.ami.data

import org.junit.Assert.*
import org.junit.Test

class ArtworkIdentityTest {
    @Test fun sizesAndCdnShardsShareTheOriginalWithoutMergingOtherParameters() {
        val original = "https://p1.music.126.net/hash/123.jpg"
        assertEquals(original, ArtworkDownloader.sharedUrl("http://p2.music.126.net/hash/123.jpg?param=80y80"))
        assertEquals(original, ArtworkDownloader.sharedUrl(original + "?param=600y600"))
        assertEquals(original + "?other=1", ArtworkDownloader.sharedUrl(original + "?other=1&param=300y300"))
        assertNull(ArtworkDownloader.sharedUrl("https://evil.example/hash/123.jpg"))
        assertNull(ArtworkDownloader.sharedUrl("https://p1.music.126.net.evil.example/123.jpg"))
    }
}
