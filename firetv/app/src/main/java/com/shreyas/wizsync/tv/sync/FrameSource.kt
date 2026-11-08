package com.shreyas.wizsync.tv.sync

/** Supplies screen frames to the engine. */
interface FrameSource {
    /** Non-blocking: the newest frame, or null when none is available yet. */
    fun latest(): Frame?
}
