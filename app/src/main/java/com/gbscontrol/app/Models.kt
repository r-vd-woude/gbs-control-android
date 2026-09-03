package com.gbscontrol.app

enum class ConnectionStatus { DISCONNECTED, CONNECTING, CONNECTED, ERROR }

enum class ProtocolMode { UNKNOWN, API_V1, LEGACY }

data class DeviceInfo(
    val apiVersion: Int = 0,
    val firmwareVersion: String? = null,
    val deviceId: String? = null,
    val hostname: String? = null,
    val capabilities: Set<String> = emptySet(),
    val maxPageSize: Int = ApiJsonParser.DEFAULT_PAGE_SIZE,
    val slots: Int = LegacySlotParser.SLOT_COUNT,
)

data class DiscoveredDevice(
    val name: String,
    val host: String,
    val port: Int = 80,
    val apiVersion: Int? = null,
)

data class PresetSlot(
    val index: Int,
    val slot: Char,
    val name: String,
    val presetId: Int? = null,
    val scanlines: Boolean? = null,
    val scanlineStrength: Int? = null,
    val empty: Boolean = false,
)

data class PresetPage(
    val offset: Int,
    val total: Int,
    val items: List<PresetSlot>,
)

/** Outcome of a `POST /api/v1/command`; `status` is accepted, noop, invalid, busy or low_memory. */
data class CommandResult(
    val ok: Boolean,
    val status: String,
    /** Target main-loop sequence for accepted API commands; null for the legacy transport. */
    val sequence: Long? = null,
) {
    val busy: Boolean get() = status == "busy"
    val lowMemory: Boolean get() = status == "low_memory"
}

data class DeviceState(
    val sequence: Long? = null,
    val preset: String? = null,
    val presetCode: Char? = null,
    val slot: Char? = null,
    val signalPresent: Boolean? = null,
    val inputMode: String? = null,
    val scanlines: Boolean? = null,
    val scanlineStrength: Int? = null,
    val lineFilter: Boolean? = null,
    val peaking: Boolean? = null,
    val stepResponse: Boolean? = null,
    val autoGain: Boolean? = null,
    val frameTimeLock: Boolean? = null,
    val deinterlaceMode: String? = null,
    val outputComponent: Boolean? = null,
    val fullHeight: Boolean? = null,
    val matchedPresets: Boolean? = null,
    val palForce60: Boolean? = null,
    val preferScalingRgbhv: Boolean? = null,
    val calibrationAdc: Boolean? = null,
    val externalClockDisabled: Boolean? = null,
    val pictureValid: Boolean? = null,
    val brightness: Int? = null,
    val contrast: Int? = null,
    val pbGain: Int? = null,
    val prGain: Int? = null,
    val adcGain: Int? = null,
    val freeHeap: Int? = null,
)

data class AppUiState(
    val host: String = AppPrefs.DEFAULT_HOST,
    val rememberedHosts: List<String> = listOf(AppPrefs.DEFAULT_HOST),
    val discoveredDevices: List<DiscoveredDevice> = emptyList(),
    val status: ConnectionStatus = ConnectionStatus.DISCONNECTED,
    val protocol: ProtocolMode = ProtocolMode.UNKNOWN,
    val deviceInfo: DeviceInfo? = null,
    val deviceState: DeviceState = DeviceState(),
    val presets: List<PresetSlot> = emptyList(),
    val busy: Boolean = false,
    val message: String? = null,
)

enum class ControlChannel { ACTION, USER }

/**
 * How an API command is completed from the app's point of view.
 *
 * Incremental controls deliberately do not wait for every target sequence: a held button produces
 * many small adjustments, and confirming each one would serialize the gesture behind state polls.
 */
enum class CommandConfirmation { STANDARD, SLOW, INCREMENTAL }

/**
 * One user action, expressed for both transports.
 *
 * [apiName] is the `name` field of `POST /api/v1/command`, or null when API v1 has no equivalent:
 * pass-through output, the four legacy-only toggles, the frame-lock method switch. API v1 firmware
 * still serves `/sc` and `/uc`, so those commands simply take the legacy route on both firmwares,
 * and nothing is lost by preferring the API wherever it does have an equivalent.
 */
data class DeviceCommand(
    val apiName: String?,
    val apiValue: String? = null,
    val legacyCommand: Char,
    val legacyChannel: ControlChannel,
)

enum class AppScreen(val label: String) {
    DEVICES("Devices"),
    HOME("Home"),
    PRESETS("Presets"),
    PICTURE("Picture"),
    FILTERS("Filters"),
    SETTINGS("Settings"),
    LEGACY("Legacy"),
}
