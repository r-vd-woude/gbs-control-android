package com.gbscontrol.app

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import java.net.URLEncoder
import java.util.concurrent.atomic.AtomicLong

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
    private val connectionGeneration = AtomicLong(0)
    private val discovery = DeviceDiscovery(context, ::onDiscovered)
    private val mutableUiState = MutableStateFlow(
        AppUiState(
            host = prefs.host,
            rememberedHosts = prefs.rememberedHosts.sorted(),
        )
    )
    val uiState: StateFlow<AppUiState> = mutableUiState.asStateFlow()

    private val host: String get() = mutableUiState.value.host

    private data class ActiveConnection(
        val host: String,
        val generation: Long,
        val protocol: ProtocolMode,
    )

    private class IncrementalRequest(
        val connection: ActiveConnection,
        val command: DeviceCommand,
        val optimistic: (DeviceState) -> DeviceState,
    )

    // One request may wait behind the in-flight nudge. New repeats replace that waiting request,
    // preventing a long tail of stale adjustments after the user releases the button.
    private val incrementalCommands = Channel<IncrementalRequest>(
        capacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    private var incrementalRefreshJob: Job? = null

    init {
        discovery.start()
        scope.launch { processIncrementalCommands() }
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

        if (commandMutex.isLocked) {
            mutableUiState.update { it.copy(message = "Wait for the current command to finish") }
            return
        }

        val generation = connectionGeneration.incrementAndGet()
        prefs.host = normalized
        client.closeStateSocket()
        mutableUiState.update { current ->
            if (connectionGeneration.get() != generation) current else current.copy(
                host = normalized,
                rememberedHosts = prefs.rememberedHosts.sorted(),
                status = ConnectionStatus.CONNECTING,
                protocol = ProtocolMode.UNKNOWN,
                deviceInfo = null,
                deviceState = DeviceState(),
                presets = emptyList(),
                busy = false,
                message = null,
            )
        }

        val apiInfo = runCatching {
            client.get(normalized, "/api/v1/device").takeIf { it.successful }?.let { ApiJsonParser.device(it.text()) }
        }.getOrNull()

        if (!isCurrent(normalized, generation)) return
        val protocol = if (apiInfo?.apiVersion == 1) ProtocolMode.API_V1 else ProtocolMode.LEGACY
        updateIfCurrent(normalized, generation) { it.copy(protocol = protocol, deviceInfo = apiInfo) }

        val reached = if (protocol == ProtocolMode.API_V1) {
            val stateOk = refreshApiState(normalized, generation)
            val presetsOk = refreshApiPresets(normalized, generation)
            stateOk || presetsOk
        } else {
            refreshLegacyPresets(normalized, generation)
        }

        if (!isCurrent(normalized, generation)) return
        updateIfCurrent(normalized, generation) {
            it.copy(
                status = if (reached) ConnectionStatus.CONNECTED else ConnectionStatus.ERROR,
                message = if (reached) null else "Could not reach ${HostAddress.httpUrl(normalized)}",
            )
        }
        if (reached) {
            // The legacy state socket is served by API v1 firmware too, and it is the only source
            // for the four legacy-only toggles, so it is opened in both modes.
            openStateSocket(normalized, generation)
            if (protocol == ProtocolMode.API_V1) {
                scope.launch { settlePictureState(normalized, generation) }
            }
        }
    }

    fun refresh() {
        scope.launch {
            val snapshot = mutableUiState.value
            if (snapshot.status == ConnectionStatus.CONNECTING) return@launch
            val target = snapshot.host
            val generation = connectionGeneration.get()
            val ok = if (snapshot.protocol == ProtocolMode.API_V1) {
                val stateOk = refreshApiState(target, generation)
                val presetsOk = refreshApiPresets(target, generation)
                stateOk || presetsOk
            } else {
                refreshLegacyPresets(target, generation)
            }
            updateIfCurrent(target, generation) {
                it.copy(
                    status = if (ok) ConnectionStatus.CONNECTED else ConnectionStatus.ERROR,
                    message = if (ok) null else "Refresh failed",
                )
            }
        }
    }

    private fun openStateSocket(target: String, generation: Long) {
        client.openStateSocket(
            host = target,
            onOpen = {
                updateIfCurrent(target, generation) { it.copy(status = ConnectionStatus.CONNECTED) }
            },
            onState = { state ->
                updateIfCurrent(target, generation) {
                    it.copy(status = ConnectionStatus.CONNECTED, deviceState = it.deviceState.merge(state))
                }
            },
            onClosed = { reason ->
                if (isCurrent(target, generation) && mutableUiState.value.status == ConnectionStatus.CONNECTED) {
                    updateIfCurrent(target, generation) {
                        it.copy(message = reason?.let { text -> "Live state: $text" })
                    }
                }
            },
        )
    }

    private suspend fun readApiState(target: String, generation: Long): DeviceState? = runCatching {
        val response = client.get(target, "/api/v1/state")
        if (!response.successful) return@runCatching null
        val state = ApiJsonParser.state(response.text())
        updateIfCurrent(target, generation) { it.copy(deviceState = it.deviceState.merge(state)) }
        state
    }.getOrNull()

    private suspend fun refreshApiState(target: String, generation: Long): Boolean =
        readApiState(target, generation) != null

    /**
     * The device only samples the picture registers in its main loop while an API client is asking,
     * so the first state document after a quiet period reports `picture.valid = false`. One delayed
     * re-read is enough to fill the colour values in.
     */
    private suspend fun settlePictureState(target: String, generation: Long) {
        if (!isCurrent(target, generation) || mutableUiState.value.deviceState.pictureValid == true) return
        delay(PICTURE_SETTLE_MS)
        if (isCurrent(target, generation)) refreshApiState(target, generation)
    }

    private suspend fun refreshApiPresets(target: String, generation: Long): Boolean = runCatching {
        if (!isCurrent(target, generation)) return@runCatching false
        val pageSize = mutableUiState.value.deviceInfo?.maxPageSize ?: ApiJsonParser.DEFAULT_PAGE_SIZE
        val slots = mutableUiState.value.deviceInfo?.slots ?: LegacySlotParser.SLOT_COUNT
        val collected = mutableListOf<PresetSlot>()
        var offset = 0
        var total = slots
        while (offset < total && offset < slots) {
            if (!isCurrent(target, generation)) return@runCatching false
            val response = client.get(target, "/api/v1/presets?offset=$offset&limit=$pageSize")
            if (!response.successful) return@runCatching false
            val page = ApiJsonParser.presets(response.text())
            if (page.items.isEmpty()) break
            collected += page.items
            total = page.total.coerceAtMost(slots)
            offset += page.items.size
        }
        if (collected.isEmpty()) return@runCatching false
        updateIfCurrent(target, generation) {
            it.copy(presets = collected.distinctBy(PresetSlot::index).sortedBy(PresetSlot::index))
        }
        true
    }.getOrDefault(false)

    private suspend fun refreshLegacyPresets(target: String, generation: Long): Boolean = runCatching {
        if (!isCurrent(target, generation)) return@runCatching false
        val response = client.get(target, "/bin/slots.bin?nocache=${System.currentTimeMillis()}")
        if (!response.successful) return@runCatching false
        val presets = LegacySlotParser.parse(response.body)
        if (presets.isEmpty()) return@runCatching false
        updateIfCurrent(target, generation) { it.copy(presets = presets) }
        true
    }.getOrDefault(false)

    fun execute(
        command: DeviceCommand,
        confirmation: CommandConfirmation = CommandConfirmation.STANDARD,
        optimistic: (DeviceState) -> DeviceState = { it },
    ) {
        val connection = currentConnection() ?: return
        if (confirmation == CommandConfirmation.INCREMENTAL) {
            incrementalCommands.trySend(IncrementalRequest(connection, command, optimistic))
            return
        }
        scope.launch {
            withCommandLock(connection) {
                var result = send(command, connection)
                if (result.ok && isCurrent(connection)) {
                    updateIfCurrent(connection) { it.copy(deviceState = optimistic(it.deviceState)) }
                    if (connection.protocol == ProtocolMode.API_V1) {
                        val timeoutMs = when (confirmation) {
                            CommandConfirmation.SLOW -> API_SLOW_CONFIRM_TIMEOUT_MS
                            CommandConfirmation.STANDARD -> API_CONFIRM_TIMEOUT_MS
                            CommandConfirmation.INCREMENTAL -> error("Incremental commands use their worker")
                        }
                        val confirmed = awaitApiSequence(connection, result.sequence, timeoutMs)
                        if (!confirmed && isCurrent(connection)) {
                            result = CommandResult(false, "confirmation_timeout", result.sequence)
                        } else {
                            settlePictureState(connection.host, connection.generation)
                        }
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

    fun loadPreset(slot: PresetSlot) {
        val connection = currentConnection() ?: return
        scope.launch {
            withCommandLock(connection) {
                var result = if (connection.protocol == ProtocolMode.API_V1) {
                    // API v1 takes the slot character, not the index, and makes it the startup preset.
                    sendApiCommand(connection.host, "activate_preset", slot.slot.toString())
                } else {
                    val selected = client.get(connection.host, "/slot/set?slot=${slot.slot}").successful
                    if (selected) {
                        sendLegacyCommand(connection.host, ControlChannel.USER, '3')
                    } else {
                        CommandResult(false, "error")
                    }
                }
                if (result.ok && isCurrent(connection)) {
                    if (connection.protocol == ProtocolMode.API_V1) {
                        if (!awaitApiSequence(connection, result.sequence, API_SLOW_CONFIRM_TIMEOUT_MS) &&
                            isCurrent(connection)
                        ) {
                            result = CommandResult(false, "confirmation_timeout", result.sequence)
                        }
                    } else {
                        updateIfCurrent(connection) {
                            it.copy(deviceState = it.deviceState.copy(slot = slot.slot))
                        }
                    }
                }
                result
            }
        }
    }

    /**
     * Saving and removing presets are not part of API v1; the firmware keeps serving the legacy
     * `/slot/save` and `/slot/remove` routes on every version, so these always take that path.
     */
    fun savePreset(slot: PresetSlot, name: String) {
        val connection = currentConnection() ?: return
        scope.launch {
            withCommandLock(connection, refreshPresets = true) {
                val safeName = truncateUtf8(name, PRESET_NAME_MAX_BYTES)
                val encoded = URLEncoder.encode(safeName, Charsets.UTF_8.name())
                client.get(connection.host, "/slot/save?index=${slot.index}&name=$encoded").toCommandResult()
            }
        }
    }

    fun removePreset(slot: PresetSlot) {
        val connection = currentConnection() ?: return
        scope.launch {
            withCommandLock(connection, refreshPresets = true) {
                // The firmware clears the selected slot in two steps, as the web UI does.
                val selected = client.get(connection.host, "/slot/set?slot=${slot.slot}")
                if (!selected.successful) return@withCommandLock CommandResult(false, "error")
                val first = client.get(connection.host, "/slot/remove?0").toCommandResult()
                if (!first.ok) return@withCommandLock first
                delay(REMOVE_STEP_MS)
                client.get(connection.host, "/slot/remove?1").toCommandResult()
            }
        }
    }

    private suspend fun withCommandLock(
        connection: ActiveConnection,
        refreshPresets: Boolean = false,
        block: suspend () -> CommandResult,
    ) {
        if (!isCurrent(connection)) return
        if (!commandMutex.tryLock()) {
            updateIfCurrent(connection) { it.copy(message = "A command is already in progress") }
            return
        }
        updateIfCurrent(connection) { it.copy(busy = true, message = null) }
        try {
            val result = block()
            if (result.ok && refreshPresets && isCurrent(connection)) {
                if (connection.protocol == ProtocolMode.API_V1) {
                    refreshApiPresets(connection.host, connection.generation)
                } else {
                    refreshLegacyPresets(connection.host, connection.generation)
                }
            }
            if (!result.ok && isCurrent(connection)) {
                updateIfCurrent(connection) { it.copy(message = result.describe()) }
            }
        } catch (error: Exception) {
            updateIfCurrent(connection) { it.copy(message = error.message ?: "Command failed") }
        } finally {
            updateIfCurrent(connection) { it.copy(busy = false) }
            commandMutex.unlock()
        }
    }

    private suspend fun send(command: DeviceCommand, connection: ActiveConnection): CommandResult =
        if (connection.protocol == ProtocolMode.API_V1 && command.apiName != null) {
            sendApiCommand(connection.host, command.apiName, command.apiValue)
        } else {
            sendLegacyCommand(connection.host, command.legacyChannel, command.legacyCommand)
        }

    private suspend fun sendApiCommand(target: String, name: String, value: String?): CommandResult {
        val fields = buildMap {
            put("name", name)
            value?.let { put("value", it) }
        }
        val response = client.postForm(target, "/api/v1/command", fields)
        return ApiJsonParser.commandResult(response.code, response.text())
    }

    private suspend fun sendLegacyCommand(target: String, channel: ControlChannel, command: Char): CommandResult {
        val route = if (channel == ControlChannel.ACTION) "/sc" else "/uc"
        val key = URLEncoder.encode(command.toString(), Charsets.UTF_8.name())
        return client.get(target, "$route?$key").toCommandResult()
    }

    private suspend fun processIncrementalCommands() {
        for (request in incrementalCommands) {
            val connection = request.connection
            if (!isCurrent(connection) || !commandMutex.tryLock()) continue
            try {
                var result = send(request.command, connection)
                if (result.busy && isCurrent(connection)) {
                    // A previous nudge can still occupy the firmware's one-entry command queue
                    // after its HTTP response. Retry once; a continuing hold supplies later tries.
                    delay(INCREMENTAL_BUSY_RETRY_MS)
                    if (isCurrent(connection)) result = send(request.command, connection)
                }

                if (result.ok && isCurrent(connection)) {
                    updateIfCurrent(connection) {
                        it.copy(deviceState = request.optimistic(it.deviceState))
                    }
                    scheduleIncrementalStateRefresh(connection)
                } else if (!result.busy && isCurrent(connection)) {
                    updateIfCurrent(connection) { it.copy(message = result.describe()) }
                }
            } catch (error: Exception) {
                updateIfCurrent(connection) { it.copy(message = error.message ?: "Command failed") }
            } finally {
                commandMutex.unlock()
            }
        }
    }

    private fun scheduleIncrementalStateRefresh(connection: ActiveConnection) {
        if (connection.protocol != ProtocolMode.API_V1) return
        incrementalRefreshJob?.cancel()
        incrementalRefreshJob = scope.launch {
            delay(INCREMENTAL_REFRESH_DEBOUNCE_MS)
            if (!isCurrent(connection)) return@launch
            refreshApiState(connection.host, connection.generation)
            settlePictureState(connection.host, connection.generation)
        }
    }

    private suspend fun awaitApiSequence(
        connection: ActiveConnection,
        targetSequence: Long?,
        timeoutMs: Long,
    ): Boolean {
        // Older or non-conforming API responses may omit the target. Preserve compatibility by
        // doing one state refresh, but only the sequence-bearing response can prove completion.
        if (targetSequence == null) {
            return refreshApiState(connection.host, connection.generation)
        }

        val deadline = System.nanoTime() + timeoutMs * NANOS_PER_MILLISECOND
        var pollAttempt = 0
        while (true) {
            if (!isCurrent(connection)) return false
            val remainingNanos = deadline - System.nanoTime()
            if (remainingNanos <= 0) return false
            val delayMs = minOf(
                confirmationPollDelayMs(pollAttempt++),
                (remainingNanos / NANOS_PER_MILLISECOND).coerceAtLeast(1L),
            )
            delay(delayMs)
            if (System.nanoTime() >= deadline || !isCurrent(connection)) return false
            val state = readApiState(connection.host, connection.generation)
            if (!isCurrent(connection)) return false
            val current = state?.sequence
            if (current != null && sequenceHasReached(current, targetSequence)) return true
        }
    }

    private fun currentConnection(): ActiveConnection? {
        val current = mutableUiState.value
        if (current.status != ConnectionStatus.CONNECTED || current.protocol == ProtocolMode.UNKNOWN) {
            mutableUiState.update { it.copy(message = "Connect to a device first") }
            return null
        }
        return ActiveConnection(current.host, connectionGeneration.get(), current.protocol)
    }

    private fun isCurrent(connection: ActiveConnection): Boolean =
        isCurrent(connection.host, connection.generation)

    private fun isCurrent(target: String, generation: Long): Boolean =
        connectionGeneration.get() == generation && host == target

    private inline fun updateIfCurrent(
        connection: ActiveConnection,
        transform: (AppUiState) -> AppUiState,
    ) = updateIfCurrent(connection.host, connection.generation, transform)

    private inline fun updateIfCurrent(
        target: String,
        generation: Long,
        transform: (AppUiState) -> AppUiState,
    ) {
        if (connectionGeneration.get() != generation) return
        mutableUiState.update { current ->
            if (connectionGeneration.get() == generation && current.host == target) {
                transform(current)
            } else {
                current
            }
        }
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
        connectionGeneration.incrementAndGet()
        incrementalRefreshJob?.cancel()
        incrementalCommands.close()
        discovery.stop()
        client.shutdown()
    }

    private companion object {
        const val API_CONFIRM_TIMEOUT_MS = 4_000L
        const val API_SLOW_CONFIRM_TIMEOUT_MS = 12_000L
        const val NANOS_PER_MILLISECOND = 1_000_000L

        const val INCREMENTAL_BUSY_RETRY_MS = 100L
        const val INCREMENTAL_REFRESH_DEBOUNCE_MS = 650L

        /** The device re-reads the picture registers every 500 ms while an API client is asking. */
        const val PICTURE_SETTLE_MS = 700L

        const val REMOVE_STEP_MS = 250L
        const val PRESET_NAME_MAX_BYTES = 24
    }
}

private fun CommandResult.describe(): String = when {
    busy -> "The device is still running the previous command"
    lowMemory -> "The device is low on memory; try again in a moment"
    status == "invalid" -> "The device rejected that command"
    status == "confirmation_timeout" -> "The device accepted the command but did not confirm completion"
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
