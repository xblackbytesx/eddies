package com.eddies.app.core.ui

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Maps a coin's icon slug to the file that actually ships for it.
 *
 * scripts/refresh-icons.sh emits WebP where cwebp is installed and PNG where it
 * is not, so the extension is not knowable at compile time. Listing the asset
 * directory once and caching the result avoids both guessing and doing file I/O
 * per row while a list is scrolling.
 */
@Singleton
class IconResolver @Inject constructor(@ApplicationContext private val context: Context) {

    private val bySlug: Map<String, String> by lazy {
        runCatching {
            context.assets.list(DIR).orEmpty().associateBy { it.substringBeforeLast('.') }
        }.getOrDefault(emptyMap())
    }

    /** A `file:///android_asset/...` URI, or null when nothing ships for this coin. */
    fun uriFor(slug: String?): String? {
        if (slug.isNullOrBlank()) return null
        val file = bySlug[slug] ?: return null
        return "file:///android_asset/$DIR/$file"
    }

    val count: Int get() = bySlug.size

    private companion object {
        const val DIR = "coins"
    }
}
