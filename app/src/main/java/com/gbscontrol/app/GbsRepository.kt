package com.gbscontrol.app

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import java.net.URLEncoder

/** Names used to look a toggle up in [DeviceState]; the API v1 command names double as keys. */
object StateKeys {
    const val PAL_FORCE_60 = "palForce60"
    const val SCALING_RGBHV = "scalingRgbhv"
    const val CALIBRATION_ADC = "calibrationAdc"
    const val EXT_CLOCK_OFF = "externalClockDisabled"
}

class GbsRepository(
    context: Context,
    private val scope: CoroutineScope,
    private val client: GbsClient = GbsClient(),
) {
    private val prefs = AppPrefs(context)
    private val commandMutex = Mutex()
    private val discovery = DeviceDiscovery(context, ::onDiscovered)
    private val mutableUiState = MutableStateFlow(
        AppUiState(
            host = prefs.host,
            rememberedHosts = prefs.rememberedHosts.sorted(),
        )
    )
    val uiState: StateFlow<AppUiState> = mutableUiState.asStateFlow()

    private val host: String get() = mutableUiState.value.host
    private val onApiV1: Boolean get() = mutableUiState.value.protocol == ProtocolMode.API_V1

    init {
        discovery.start()
        scope.launch { connect(prefs.host) }
    }

    private fun onDiscovered(device: DiscoveredDevice) {
        mutableUiState.update { current ->
            val devices = (current.discoveredDevices.filterNot { it.host == device.host } + device)
                .sortedBy { it.name.lowercase() }
            current.copy(discoveredDevices = devices)
        }
    }

    suspend fun connect(rawHost: String) {
        val normalized = try {
            HostAddress.normalize(rawHost)
        } catch (error: IllegalArgumentException) {
            mutableUiState.update { it.copy(status = ConnectionStatus.ERROR, message = error.message) }
            return
        }

        prefs.host = normalized
        client.closeStateSocket()
        mutableUiState.update {
            it.copy(
                host = normalized,
                rememberedHosts = prefs.rememberedHosts.sorted(),
                status = ConnectionStatus.CONNECTING,
                protocol = ProtocolMode.UNKNOWN,
                deviceInfo = null,
                message = null,
            )
        }

        val apiInfo = runCatching {
            client.get(normalized, "/api/v1/device").takeIf { it.successful }?.let { ApiJsonParser.device(it.text()) }
        }.getOrNull()

        val protocol = if (apiInfo?.apiVersion == 1) ProtocolMode.API_V1 else ProtocolMode.LEGACY
        mutableUiState.update { it.copy(protocol = protocol, deviceInfo = apiInfo) }

        val reached = if (protocol == ProtocolMode.API_V1) {
            val stateOk = refreshApiState(normalized)
            val presetsOk = refreshApiPresets(normalized)
            stateOk || presetsOk
        } else {
            refreshLegacyPresets(normalized)
        }

        mutableUiState.update {
            it.copy(
                status = if (reached) ConnectionStatus.CONNECTED else ConnectionStatus.ERROR,
                message = if (reached) null else "Could not reach ${HostAddress.httpUrl(normalized)}",
            )
        }
        if (reached) {
            // The legacy state socket is served by API v1 firmware too, and it is the only source
            // for the four legacy-only toggles, so it is opened in both modes.
            openStateSocket(normalized)
            if (protocol == ProtocolMode.API_V1) scope.launch { settlePictureState(normalized) }
        }
    }

    fun refresh() {
        scope.launch {
            val target = host
            val ok = if (onApiV1) {
                val stateOk = refreshApiState(target)
                val presetsOk = refreshApiPresets(target)
                stateOk || presetsOk
            } else {
                refreshLegacyPresets(target)
            }
            mutableUiState.update {
                it.copy(
                    status = if (ok) ConnectionStatus.CONNECTED else ConnectionStatus.ERROR,
                    message = if (ok) null else "Refresh failed",
                )
            }
        }
    }

    private fun openStateSocket(target: String) {
        client.openStateSocket(
            host = target,
            onOpen = { mutableUiState.update { it.copy(status = ConnectionStatus.CONNECTED) } },
            onState = { state ->
                mutableUiState.update {
                    it.copy(status = ConnectionStatus.CONNECTED, deviceState = it.deviceState.merge(state))
                }
            },
            onClosed = { reason ->
                if (host == target && mutableUiState.value.status == ConnectionStatus.CONNECTED) {
                    mutableUiState.update { it.copy(message = reason?.let { text -> "Live state: $text" }) }
                }
            },
        )
    }

    private suspend fun refreshApiState(target: String): Boolean = runCatching {
        val response = client.get(target, "/api/v1/state")
        if (!response.successful) return@runCatching false
        val state = ApiJsonParser.state(response.text())
        mutableUiState.update { it.copy(deviceState = it.deviceState.merge(state)) }
        true
    }.getOrDefault(false)

    /**
     * The device only samples the picture registers in its main loop while an API client is asking,
     * so the first state document after a quiet period reports `picture.valid = false`. One delayed
     * re-read is enough to fill the colour values in.
     */
    private suspend fun settlePictureState(target: String) {
        if (mutableUiState.value.deviceState.pictureValid == true) return
        delay(PICTURE_SETTLE_MS)
        if (host == target) refreshApiState(target)
    }

    private suspend fun refreshApiPresets(target: String): Boolean = runCatching {
        val pageSize = mutableUiState.value.deviceInfo?.maxPageSize ?: ApiJsonParser.DEFAULT_PAGE_SIZE
        val slots = mutableUiState.value.deviceInfo?.slots ?: LegacySlotParser.SLOT_COUNT
        val collected = mutableListOf<PresetSlot>()
        var offset = 0
        var total = slots
        while (offset < total && offset < slots) {
            val response = client.get(target, "/api/v1/presets?offset=$offset&limit=$pageSize")
            if (!response.successful) return@runCatching false
            val page = ApiJsonParser.presets(response.text())
            if (page.items.isEmpty()) break
            collected += page.items
            total = page.total.coerceAtMost(slots)
            offset += page.items.size
        }
        if (collected.isEmpty()) return@runCatching false
        mutableUiState.update {
            it.copy(presets = collected.distinctBy(PresetSlot::index).sortedBy(PresetSlot::index))
        }
        true
    }.getOrDefault(false)

    private suspend fun refreshLegacyPresets(target: String): Boolean = runCatching {
        val response = client.get(target, "/bin/slots.bin?nocache=${System.currentTimeMillis()}")
        if (!response.successful) return@runCatching false
        val presets = LegacySlotParser.parse(response.body)
        if (presets.isEmpty()) return@runCatching false
        mutableUiState.update { it.copy(presets = presets) }
        true
    }.getOrDefault(false)

    fun execute(command: DeviceCommand, optimistic: (DeviceState) -> DeviceState = { it }) {
        scope.launch {
            withCommandLock {
                val result = send(command)
                if (result.ok) {
                    mutableUiState.update { it.copy(deviceState = optimistic(it.deviceState)) }
                    if (onApiV1) {
                        delay(STATE_SETTLE_MS)
                        refreshApiState(host)
                        settlePictureState(host)
                    }
                }
                result
            }
        }
    }

    /**
     * Sends only when the device is known to be in the other position. API v1 would answer `noop`
     * anyway, but skipping the request also keeps legacy firmware - where every command is a blind
     * toggle - from flipping a setting the user did not touch.
     */
    fun setToggle(stateKey: String, desired: Boolean, command: DeviceCommand) {
        if (mutableUiState.value.deviceState.option(stateKey) == desired) return
        execute(command) { it.withOption(stateKey, desired) }
    }

    fun loadPreset(slot: PresetSlot) = scope.launch {
        withCommandLock {
            val result = if (onApiV1) {
                // API v1 takes the slot character, not the index, and makes it the startup preset.
                sendApiCommand("activate_preset", slot.slot.toString())
            } else {
                val selected = client.get(host, "/slot/set?slot=${slot.slot}").successful
                if (selected) sendLegacyCommand(ControlChannel.USER, '3') else CommandResult(false, "error")
            }
            if (result.ok) {
                mutableUiState.update { it.copy(deviceState = it.deviceState.copy(slot = slot.slot)) }
                if (onApiV1) {
                    delay(STATE_SETTLE_MS)
                    refreshApiState(host)
                }
            }
            result
        }
    }

    /**
     * Saving and removing presets are not part of API v1; the firmware keeps serving the legacy
     * `/slot/save` and `/slot/remove` routes on every version, so these always take that path.
     */
    fun savePreset(slot: PresetSlot, name: String) = scope.launch {
        withCommandLock(refreshPresets = true) {
            val encoded = URLEncoder.encode(name.take(24), Charsets.UTF_8.name())
            client.get(host, "/slot/save?index=${slot.index}&name=$encoded").toCommandResult()
        }
    }

    fun removePreset(slot: PresetSlot) = scope.launch {
        withCommandLock(refreshPresets = true) {
            // The firmware clears the selected slot in two steps, as the web UI does.
            val selected = client.get(host, "/slot/set?slot=${slot.slot}")
            if (!selected.successful) return@withCommandLock CommandResult(false, "error")
            val first = client.get(host, "/slot/remove?0").toCommandResult()
            if (!first.ok) return@withCommandLock first
            delay(REMOVE_STEP_MS)
            client.get(host, "/slot/remove?1").toCommandResult()
        }
    }

    private suspend fun withCommandLock(
        refreshPresets: Boolean = false,
        block: suspend () -> CommandResult,
    ) {
        if (!commandMutex.tryLock()) {
            mutableUiState.update { it.copy(message = "A command is already in progress") }
            return
        }
        mutableUiState.update { it.copy(busy = true, message = null) }
        try {
            val result = block()
            if (result.ok && refreshPresets) {
                if (onApiV1) refreshApiPresets(host) else refreshLegacyPresets(host)
            }
            if (!result.ok) {
                mutableUiState.update { it.copy(message = result.describe()) }
            }
        } catch (error: Exception) {
            mutableUiState.update { it.copy(message = error.message ?: "Command failed") }
        } finally {
            mutableUiState.update { it.copy(busy = false) }
            commandMutex.unlock()
        }
    }

    private suspend fun send(command: DeviceCommand): CommandResult =
        if (onApiV1 && command.apiName != null) {
            sendApiCommand(command.apiName, command.apiValue)
        } else {
            sendLegacyCommand(command.legacyChannel, command.legacyCommand)
        }

    private suspend fun sendApiCommand(name: String, value: String?): CommandResult {
        val fields = buildMap {
            put("name", name)
            value?.let { put("value", it) }
        }
        val response = client.postForm(host, "/api/v1/command", fields)
        return ApiJsonParser.commandResult(response.code, response.text())
    }

    private suspend fun sendLegacyCommand(channel: ControlChannel, command: Char): CommandResult {
        val route = if (channel == ControlChannel.ACTION) "/sc" else "/uc"
        val key = URLEncoder.encode(command.toString(), Charsets.UTF_8.name())
        return client.get(host, "$route?$key").toCommandResult()
    }

    /** Legacy routes answer an empty body, a bare `true`/`false`, or nothing but a status code. */
    private fun HttpResult.toCommandResult(): CommandResult {
        if (!successful) return CommandResult(false, "http_$code")
        val payload = text().trim()
        if (payload.equals("false", ignoreCase = true)) return CommandResult(false, "rejected")
        return CommandResult(true, ApiJsonParser.STATUS_ACCEPTED)
    }

    fun clearMessage() = mutableUiState.update { it.copy(message = null) }

    fun forgetHost(value: String) {
        prefs.forgetHost(value)
        mutableUiState.update { it.copy(rememberedHosts = prefs.rememberedHosts.sorted()) }
    }

    fun close() {
        discovery.stop()
        client.shutdown()
    }

    private companion object {
        /** Long enough for loop() to pick the queued command up, short enough to feel immediate. */
        const val STATE_SETTLE_MS = 250L

        /** The device re-reads the picture registers every 500 ms while an API client is asking. */
        const val PICTURE_SETTLE_MS = 700L

        const val REMOVE_STEP_MS = 250L
    }
}

private fun CommandResult.describe(): String = when {
    busy -> "The device is still running the previous command"
    lowMemory -> "The device is low on memory; try again in a moment"
    status == "invalid" -> "The device rejected that command"
    else -> "Command was rejected"
}

private fun DeviceState.option(name: String): Boolean? = when (name) {
    "set_scanlines" -> scanlines
    "set_line_filter" -> lineFilter
    "set_peaking" -> peaking
    "set_step_response" -> stepResponse
    "set_auto_gain" -> autoGain
    "set_frame_time_lock" -> frameTimeLock
    "set_output_component" -> outputComponent
    "set_full_height" -> fullHeight
    "set_matched_presets" -> matchedPresets
    StateKeys.PAL_FORCE_60 -> palForce60
    StateKeys.SCALING_RGBHV -> preferScalingRgbhv
    StateKeys.CALIBRATION_ADC -> calibrationAdc
    StateKeys.EXT_CLOCK_OFF -> externalClockDisabled
    else -> null
}

private fun DeviceState.withOption(name: String, value: Boolean): DeviceState = when (name) {
    "set_scanlines" -> copy(scanlines = value)
    "set_line_filter" -> copy(lineFilter = value)
    "set_peaking" -> copy(peaking = value)
    "set_step_response" -> copy(stepResponse = value)
    "set_auto_gain" -> copy(autoGain = value)
    "set_frame_time_lock" -> copy(frameTimeLock = value)
    "set_output_component" -> copy(outputComponent = value)
    "set_full_height" -> copy(fullHeight = value)
    "set_matched_presets" -> copy(matchedPresets = value)
    StateKeys.PAL_FORCE_60 -> copy(palForce60 = value)
    StateKeys.SCALING_RGBHV -> copy(preferScalingRgbhv = value)
    StateKeys.CALIBRATION_ADC -> copy(calibrationAdc = value)
    StateKeys.EXT_CLOCK_OFF -> copy(externalClockDisabled = value)
    else -> this
}

/** Field-wise fill-in, so a WebSocket frame never erases values only the API reports, and back. */
internal fun DeviceState.merge(new: DeviceState) = DeviceState(
    sequence = new.sequence ?: sequence,
    preset = new.preset ?: preset,
    presetCode = new.presetCode ?: presetCode,
    slot = new.slot ?: slot,
    signalPresent = new.signalPresent ?: signalPresent,
    inputMode = new.inputMode ?: inputMode,
    scanlines = new.scanlines ?: scanlines,
    scanlineStrength = new.scanlineStrength ?: scanlineStrength,
    lineFilter = new.lineFilter ?: lineFilter,
    peaking = new.peaking ?: peaking,
    stepResponse = new.stepResponse ?: stepResponse,
    autoGain = new.autoGain ?: autoGain,
    frameTimeLock = new.frameTimeLock ?: frameTimeLock,
    deinterlaceMode = new.deinterlaceMode ?: deinterlaceMode,
    outputComponent = new.outputComponent ?: outputComponent,
    fullHeight = new.fullHeight ?: fullHeight,
    matchedPresets = new.matchedPresets ?: matchedPresets,
    palForce60 = new.palForce60 ?: palForce60,
    preferScalingRgbhv = new.preferScalingRgbhv ?: preferScalingRgbhv,
    calibrationAdc = new.calibrationAdc ?: calibrationAdc,
    externalClockDisabled = new.externalClockDisabled ?: externalClockDisabled,
    pictureValid = new.pictureValid ?: pictureValid,
    brightness = new.brightness ?: brightness,
    contrast = new.contrast ?: contrast,
    pbGain = new.pbGain ?: pbGain,
    prGain = new.prGain ?: prGain,
    adcGain = new.adcGain ?: adcGain,
    freeHeap = new.freeHeap ?: freeHeap,
)
