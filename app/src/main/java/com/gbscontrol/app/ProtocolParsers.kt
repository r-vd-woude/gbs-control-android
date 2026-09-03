package com.gbscontrol.app

import org.json.JSONArray
import org.json.JSONObject

/**
 * Labels shared by both transports. The firmware reports the same preset digit in byte 1 of the
 * legacy WebSocket frame and in `preset` of `GET /api/v1/state`, so one table serves both and the
 * UI never has to know which transport a value arrived on.
 */
object PresetLabels {
    const val UNKNOWN = "Unknown"

    private val byDigit = mapOf(
        '0' to UNKNOWN,
        '1' to "1280x960",
        '2' to "1280x1024",
        '3' to "1280x720",
        '4' to "480p / 576p",
        '5' to "1920x1080",
        '6' to "Downscale (15 kHz)",
        '8' to "Pass-through",
        '9' to "Custom",
    )

    fun forDigit(digit: Char): String = byDigit[digit] ?: UNKNOWN
}

/** `rto->videoStandardInput`, as reported by `inputMode` in the API v1 state document. */
object InputModeLabels {
    private val byCode = mapOf(
        0 to "No signal",
        1 to "NTSC 240p / 480i",
        2 to "PAL 288p / 576i",
        3 to "480p",
        4 to "576p",
        5 to "720p",
        6 to "1080i",
        7 to "1080p",
        8 to "24 kHz",
        13 to "Component VGA",
        14 to "RGBHV (scaled)",
        15 to "RGBHV (bypass)",
    )

    fun forCode(code: Int): String = byCode[code] ?: "Mode $code"
}

/** Canonical display values, so a mode read over either transport compares equal. */
object DeinterlaceModes {
    const val MOTION_ADAPTIVE = "Motion adaptive"
    const val BOB = "Bob"

    /** The value the API v1 `set_deinterlace` command expects. */
    fun apiValue(display: String): String =
        if (display.equals(BOB, ignoreCase = true)) "bob" else "motion_adaptive"

    fun fromApi(value: String?): String? = when {
        value == null -> null
        value.equals("bob", ignoreCase = true) -> BOB
        else -> MOTION_ADAPTIVE
    }
}

object LegacySlotParser {
    const val SLOT_COUNT = 72
    const val RECORD_SIZE = 32
    const val NAME_SIZE = 25
    const val SLOT_CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~()!*:,"

    fun parse(bytes: ByteArray): List<PresetSlot> {
        if (bytes.size < RECORD_SIZE) return emptyList()
        val count = minOf(SLOT_COUNT, bytes.size / RECORD_SIZE)
        return (0 until count).map { index ->
            val base = index * RECORD_SIZE
            val rawName = buildString {
                for (offset in 0 until NAME_SIZE) {
                    val value = bytes[base + offset].toInt() and 0xff
                    if (value == 0) break
                    if (value in 32..126) append(value.toChar()) else break
                }
            }.trim()
            val empty = rawName.isBlank() || rawName.equals("Empty", ignoreCase = true)
            PresetSlot(
                index = index,
                slot = SLOT_CHARS[index],
                name = if (empty) "Slot ${index + 1}" else rawName,
                presetId = bytes[base + 25].toInt() and 0xff,
                scanlines = (bytes[base + 26].toInt() and 0xff) != 0,
                scanlineStrength = bytes[base + 27].toInt() and 0xff,
                empty = empty,
            )
        }
    }
}

/** The six-byte `#...` frame the firmware pushes over `/ws`, served by every firmware version. */
object LegacyStateParser {
    fun parse(message: String): DeviceState? {
        if (message.length < 6 || message[0] != '#') return null
        val flags0 = message[3].code
        val flags1 = message[4].code
        val flags2 = message[5].code
        val presetCode = message[1]
        return DeviceState(
            preset = PresetLabels.forDigit(presetCode),
            presetCode = presetCode,
            slot = message[2],
            autoGain = flags0 and 0x01 != 0,
            scanlines = flags0 and 0x02 != 0,
            lineFilter = flags0 and 0x04 != 0,
            peaking = flags0 and 0x08 != 0,
            palForce60 = flags0 and 0x10 != 0,
            outputComponent = flags0 and 0x20 != 0,
            matchedPresets = flags1 and 0x01 != 0,
            frameTimeLock = flags1 and 0x02 != 0,
            // Bit 2 is set when deintMode is 1, which the firmware documents as Bob.
            deinterlaceMode = if (flags1 and 0x04 != 0) DeinterlaceModes.BOB else DeinterlaceModes.MOTION_ADAPTIVE,
            stepResponse = flags1 and 0x10 != 0,
            fullHeight = flags1 and 0x20 != 0,
            calibrationAdc = flags2 and 0x01 != 0,
            preferScalingRgbhv = flags2 and 0x02 != 0,
            externalClockDisabled = flags2 and 0x04 != 0,
        )
    }
}

/**
 * Parsers for the firmware's HTTP API v1. Field names follow the "HTTP API v1" section of the
 * firmware README; anything the device omits stays null so [DeviceState.merge] keeps whatever the
 * WebSocket frame already supplied.
 */
object ApiJsonParser {
    const val DEFAULT_PAGE_SIZE = 8

    fun device(json: String): DeviceInfo {
        val root = JSONObject(json)
        return DeviceInfo(
            apiVersion = root.optInt("apiVersion", 0),
            firmwareVersion = root.stringOrNull("firmwareVersion"),
            deviceId = root.stringOrNull("deviceId"),
            hostname = root.stringOrNull("hostname"),
            capabilities = root.optJSONArray("capabilities").toStringSet(),
            maxPageSize = root.intOrNull("maxPageSize") ?: DEFAULT_PAGE_SIZE,
            slots = root.intOrNull("slots") ?: LegacySlotParser.SLOT_COUNT,
        )
    }

    fun state(json: String): DeviceState {
        val root = JSONObject(json)
        val picture = root.optJSONObject("picture")
        val pictureValid = picture?.boolOrNull("valid") ?: false
        // Only trust the picture registers once loop() has actually sampled them; before the first
        // sample the firmware reports valid=false rather than stale zeroes.
        val readable = picture?.takeIf { pictureValid }
        val presetDigit = root.intOrNull("preset")?.takeIf { it in 0..9 }?.let { '0' + it }
        return DeviceState(
            sequence = root.longOrNull("sequence"),
            preset = presetDigit?.let(PresetLabels::forDigit),
            presetCode = presetDigit,
            slot = root.stringOrNull("slot")?.firstOrNull(),
            signalPresent = root.boolOrNull("signalPresent"),
            inputMode = root.intOrNull("inputMode")?.let(InputModeLabels::forCode),
            scanlines = root.boolOrNull("scanlines"),
            scanlineStrength = root.intOrNull("scanlineStrength"),
            lineFilter = root.boolOrNull("lineFilter"),
            peaking = root.boolOrNull("peaking"),
            stepResponse = root.boolOrNull("stepResponse"),
            autoGain = root.boolOrNull("autoGain"),
            frameTimeLock = root.boolOrNull("frameTimeLock"),
            deinterlaceMode = DeinterlaceModes.fromApi(root.stringOrNull("deinterlaceMode")),
            outputComponent = root.boolOrNull("outputComponent"),
            fullHeight = root.boolOrNull("fullHeight"),
            matchedPresets = root.boolOrNull("matchedPresets"),
            palForce60 = root.boolOrNull("palForce60"),
            preferScalingRgbhv = root.boolOrNull("scalingRgbhv"),
            // Not in the v1 state document; it keeps arriving on the WebSocket frame.
            calibrationAdc = null,
            // The firmware reports whether the generator is enabled; the UI toggles the opposite.
            externalClockDisabled = root.boolOrNull("externalClockGen")?.not(),
            pictureValid = pictureValid,
            brightness = readable?.intOrNull("brightness"),
            contrast = readable?.intOrNull("contrast"),
            pbGain = readable?.intOrNull("pbGain"),
            prGain = readable?.intOrNull("prGain"),
            adcGain = readable?.intOrNull("adcGain"),
            freeHeap = root.optJSONObject("memory")?.intOrNull("heap"),
        )
    }

    fun presets(json: String): PresetPage {
        val root = JSONObject(json)
        val offset = root.optInt("offset", 0)
        val total = root.optInt("total", 0)
        val items = root.optJSONArray("items") ?: JSONArray()
        val parsed = buildList {
            for (i in 0 until items.length()) {
                val item = items.optJSONObject(i) ?: continue
                val index = item.optInt("index", offset + i)
                val slot = item.stringOrNull("slot")?.firstOrNull()
                    ?: LegacySlotParser.SLOT_CHARS.getOrElse(index) { '?' }
                val rawName = item.stringOrNull("name").orEmpty().trim()
                // The device reports `populated` directly; fall back to the name if it is absent.
                val empty = item.boolOrNull("populated")?.not()
                    ?: (rawName.isBlank() || rawName.equals("Empty", ignoreCase = true))
                add(
                    PresetSlot(
                        index = index,
                        slot = slot,
                        name = if (empty || rawName.isBlank()) "Slot ${index + 1}" else rawName,
                        presetId = item.intOrNull("presetId"),
                        scanlines = item.boolOrNull("scanlines"),
                        scanlineStrength = item.intOrNull("scanlineStrength"),
                        empty = empty,
                    )
                )
            }
        }
        return PresetPage(offset, if (total > 0) total else parsed.size, parsed)
    }

    /** Reads the `{"ok":…,"status":…}` envelope every `POST /api/v1/command` answers with. */
    fun commandResult(code: Int, body: String): CommandResult {
        val successfulCode = code in 200..299
        val json = runCatching { JSONObject(body.trim()) }.getOrNull()
            ?: return CommandResult(successfulCode, if (successfulCode) STATUS_ACCEPTED else "http_$code")
        val status = json.stringOrNull("status") ?: if (successfulCode) STATUS_ACCEPTED else "error"
        // `ok` is authoritative when present; anything else falls back to the status code.
        val ok = if (json.has("ok")) json.optBoolean("ok") else successfulCode
        return CommandResult(ok, status)
    }

    const val STATUS_ACCEPTED = "accepted"

    private fun JSONArray?.toStringSet(): Set<String> {
        if (this == null) return emptySet()
        return buildSet { for (i in 0 until length()) optString(i).takeIf(String::isNotBlank)?.let(::add) }
    }

    private fun JSONObject.stringOrNull(name: String): String? =
        if (has(name) && !isNull(name)) optString(name).takeIf(String::isNotBlank) else null

    private fun JSONObject.intOrNull(name: String): Int? =
        if (has(name) && !isNull(name)) optInt(name) else null

    private fun JSONObject.longOrNull(name: String): Long? =
        if (has(name) && !isNull(name)) optLong(name) else null

    private fun JSONObject.boolOrNull(name: String): Boolean? =
        if (has(name) && !isNull(name)) optBoolean(name) else null
}
