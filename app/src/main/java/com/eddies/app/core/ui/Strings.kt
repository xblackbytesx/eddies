package com.eddies.app.core.ui

import android.content.Context
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Translated text for code that is not a composable.
 *
 * A screen asks `stringResource` directly. A view model has no composition to
 * ask, and the messages it produces ("Backup written.") are sentences a person
 * reads like any other, so they come from the same resource files through this.
 */
@Singleton
class Strings @Inject constructor(@param:ApplicationContext private val context: Context) {

    operator fun invoke(@StringRes id: Int, vararg args: Any): String = context.getString(id, *args)

    /** The plural form for [count], with [count] as its first argument. */
    fun plural(@PluralsRes id: Int, count: Int, vararg args: Any): String =
        context.resources.getQuantityString(id, count, count, *args)
}
