package com.shreyas.wizsync.tv.store

import android.content.Context

/** [KeyValueStore] backed by the app's private SharedPreferences file "wizsync". */
class PrefsStore(ctx: Context) : KeyValueStore {
    private val prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun get(key: String): String? = prefs.getString(key, null)

    override fun put(key: String, value: String) {
        prefs.edit().putString(key, value).apply()
    }

    companion object {
        const val PREFS_NAME = "wizsync"
    }
}
