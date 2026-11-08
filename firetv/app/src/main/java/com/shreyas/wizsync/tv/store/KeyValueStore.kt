package com.shreyas.wizsync.tv.store

/** A minimal string key-value store. Implemented by [PrefsStore] on device and [MapStore] in tests. */
interface KeyValueStore {
    fun get(key: String): String?
    fun put(key: String, value: String)
}

/** In-memory [KeyValueStore]; used by unit tests and anywhere persistence is not needed. */
class MapStore : KeyValueStore {
    private val map = mutableMapOf<String, String>()

    override fun get(key: String): String? = map[key]

    override fun put(key: String, value: String) {
        map[key] = value
    }
}
