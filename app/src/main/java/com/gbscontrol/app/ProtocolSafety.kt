package com.gbscontrol.app

/**
 * Truncates text to the firmware slot-name field without splitting a UTF-8 code point.
 * The on-device field is 25 bytes including its terminating NUL, not 25 Kotlin characters.
 */
internal fun truncateUtf8(value: String, maxBytes: Int): String {
    require(maxBytes >= 0) { "maxBytes must not be negative" }
    val result = StringBuilder()
    var byteCount = 0
    var index = 0
    while (index < value.length) {
        val codePoint = value.codePointAt(index)
        val encoded = String(Character.toChars(codePoint)).toByteArray(Charsets.UTF_8)
        if (byteCount + encoded.size > maxBytes) break
        result.appendCodePoint(codePoint)
        byteCount += encoded.size
        index += Character.charCount(codePoint)
    }
    return result.toString()
}

/** Unsigned 32-bit comparison that remains correct when the firmware sequence wraps to zero. */
internal fun sequenceHasReached(current: Long, target: Long): Boolean {
    val delta = (current - target) and UINT32_MASK
    return delta < UINT32_HALF_RANGE
}

/** Backoff used when confirming that the firmware main loop completed an accepted command. */
internal fun confirmationPollDelayMs(attempt: Int): Long {
    require(attempt >= 0) { "attempt must not be negative" }
    return CONFIRMATION_POLL_DELAYS_MS[minOf(attempt, CONFIRMATION_POLL_DELAYS_MS.lastIndex)]
}

private const val UINT32_MASK = 0xffff_ffffL
private const val UINT32_HALF_RANGE = 0x8000_0000L
private val CONFIRMATION_POLL_DELAYS_MS = longArrayOf(250L, 400L, 650L, 1_000L)
