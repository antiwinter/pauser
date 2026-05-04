package com.opentune.provider

import android.content.Context

fun interface PlaybackResumeAccessor {
    /** Returns stored position in ms, or a negative value if none. */
    fun readPositionMs(resumeKey: String): Long
}

data class PlaybackResolveDeps(
    val serverStore: ServerStore,
    val progressStore: ProgressStore,
    val androidContext: Context,
    val resumeAccessor: PlaybackResumeAccessor,
)
