package com.shilapi.xcertplay

/** Read again after startup: the OEM may change credentials while activating its hotspot. */
internal object G3HotspotConnection {
    sealed class Result {
        class Ready(val snapshot: G3HotspotSnapshot) : Result() {
            override fun toString() = "Ready(credentials=redacted)"
        }
        class ReadFailed(val status: G3HotspotStatus) : Result()
        object StartFailed : Result()
    }

    fun prepare(read: () -> G3HotspotReadResult, enable: () -> Boolean): Result {
        val initial = read()
        if (initial is G3HotspotReadResult.Failure) return Result.ReadFailed(initial.status)
        if (!enable()) return Result.StartFailed
        return when (val current = read()) {
            is G3HotspotReadResult.Failure -> Result.ReadFailed(current.status)
            is G3HotspotReadResult.Success -> if (current.snapshot.active)
                Result.Ready(current.snapshot) else Result.StartFailed
        }
    }
}
