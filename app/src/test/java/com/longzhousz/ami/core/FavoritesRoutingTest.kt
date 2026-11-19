package com.longzhousz.ami.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FavoritesRoutingTest {
    private fun local(
        direct: Boolean = true,
        hasLocalMusic: Boolean = true,
        loading: Boolean = false,
        configured: Boolean = false,
        explicitSource: String? = null,
    ) = showLocalFavorites(direct, hasLocalMusic, loading, configured, explicitSource)

    @Test fun onlyLocalMusicShowsLocalFavorites() {
        assertTrue(local(hasLocalMusic = true, configured = false))
    }

    @Test fun withoutLocalMusicTheLoginPageStays() {
        assertFalse(local(hasLocalMusic = false, configured = false))
    }

    @Test fun bothSourcesDefaultToNeteaseAndKeepAnExplicitLocalChoice() {
        assertFalse(local(configured = true))
        assertTrue(local(configured = true, explicitSource = "local"))
        assertFalse(local(configured = true, explicitSource = "cloud"))
    }

    @Test fun anExplicitLocalChoiceSurvivesTheLibraryLoad() {
        assertTrue(local(configured = true, hasLocalMusic = false, loading = true, explicitSource = "local"))
        assertFalse(local(configured = true, hasLocalMusic = false, loading = false, explicitSource = "local"))
    }

    @Test fun nonDirectBuildsKeepTheirOwnRouting() {
        assertFalse(local(direct = false, configured = false))
        assertFalse(local(direct = false, configured = true, explicitSource = "local"))
    }
}
