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
 *
 * Open, and [context] nullable, purely so a data source's unit tests can subclass this with a
 * plain in-memory fake ("id=%d") instead of needing a real Android `Context` (e.g. Robolectric) —
 * production always constructs this with a real, non-null one.
 */
open class SourceStrings(private val context: Context?) {

    open fun get(@StringRes id: Int, vararg args: Any?): String =
        if (args.isEmpty()) context!!.getString(id) else context!!.getString(id, *args)

    open fun plural(@PluralsRes id: Int, count: Int, vararg args: Any?): String =
        context!!.resources.getQuantityString(id, count, *args)
}
