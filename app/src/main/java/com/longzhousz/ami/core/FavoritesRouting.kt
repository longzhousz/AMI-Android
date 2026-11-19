package com.longzhousz.ami.core

/**
 * The bottom 我喜欢 tab shows local favorites after an explicit local choice, or when NetEase
 * is not configured. A saved local choice survives the library load so the tab does not flash
 * the online page first.
 */
fun showLocalFavorites(
    direct: Boolean,
    hasLocalMusic: Boolean,
    loading: Boolean,
    configured: Boolean,
    explicitSource: String?,
): Boolean {
    val source = explicitSource ?: if (configured) "cloud" else "local"
    return direct && source == "local" && (hasLocalMusic || (loading && explicitSource == "local"))
}
