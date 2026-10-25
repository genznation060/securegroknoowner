package com.securebrowse.app.data

import android.content.Context
import com.securebrowse.policy.StringStore

/** Plain preferences for the running focus session. This is not a secret. */
class PrefStringStore(context: Context, name: String) : StringStore {
    private val prefs = context.applicationContext.getSharedPreferences(name, Context.MODE_PRIVATE)

    override fun read(): String? = prefs.getString(KEY, null)

    override fun write(value: String) {
        prefs.edit().putString(KEY, value).commit()
    }

    private companion object {
        const val KEY = "document"
    }
}
