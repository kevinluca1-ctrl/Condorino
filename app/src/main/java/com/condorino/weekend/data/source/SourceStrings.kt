package com.condorino.weekend.data.source

import android.content.Context
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes

/**
 * Localised copy for data-source diagnostics.
 *
 * Domain and scoring stay free of Android and of any language — they emit values, and the UI
 * turns those into sentences. Data sources are different: they already do I/O and already hold a
 * `Context`, and their messages are diagnostics full of HTTP codes and counts. Handing them a
 * resource lookup is both simpler and less lossy than modelling every failure mode as a type.
 */
class SourceStrings(private val context: Context) {

    fun get(@StringRes id: Int, vararg args: Any?): String =
        if (args.isEmpty()) context.getString(id) else context.getString(id, *args)

    fun plural(@PluralsRes id: Int, count: Int, vararg args: Any?): String =
        context.resources.getQuantityString(id, count, *args)
}
