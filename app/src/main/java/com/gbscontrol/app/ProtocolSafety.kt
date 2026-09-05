package com.gbscontrol.app

/** Slot names allow 24 UTF-8 bytes, not 24 characters. */
internal fun truncateUtf8(value: String, maxBytes: Int): String {
    require(maxBytes >= 0) { "maxBytes must not be negative" }
    var byteCount = 0
    var index = 0
    while (index < value.length) {
        val codePoint = value.codePointAt(index)
        val bytes = when {
            codePoint <= 0x7f -> 1
            codePoint <= 0x7ff -> 2
            codePoint in 0xd800..0xdfff -> 1 // UTF-8 replaces an unpaired surrogate with '?'.
            codePoint <= 0xffff -> 3
            else -> 4
        }
        if (byteCount + bytes > maxBytes) break
        byteCount += bytes
        index += Character.charCount(codePoint)
    }
    return value.substring(0, index)
}

/** Compare unsigned sequences, including wraparound. */
internal fun sequenceHasReached(current: Long, target: Long): Boolean {
    val delta = (current - target) and UINT32_MASK
    return delta < UINT32_HALF_RANGE
}

/** Back off while waiting for a command. */
internal fun confirmationPollDelayMs(attempt: Int): Long {
    require(attempt >= 0) { "attempt must not be negative" }
    return CONFIRMATION_POLL_DELAYS_MS[minOf(attempt, CONFIRMATION_POLL_DELAYS_MS.lastIndex)]
}

private const val UINT32_MASK = 0xffff_ffffL
private const val UINT32_HALF_RANGE = 0x8000_0000L
private val CONFIRMATION_POLL_DELAYS_MS = longArrayOf(250L, 400L, 650L, 1_000L)
