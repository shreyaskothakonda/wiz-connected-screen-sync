package com.shreyas.wizsync.tv.sync

/** Where the engine sends bulb updates. Implementations never throw on network errors. */
interface BulbSink {
    fun send(ip: String, cmd: BulbCommand)
    fun setState(ip: String, on: Boolean)

    /** True if the bulb replied recently. */
    fun ping(ip: String): Boolean
}
