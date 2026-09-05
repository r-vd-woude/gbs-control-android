package com.gbscontrol.app

import android.content.Context
import kotlinx.coroutines.CancellationException
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
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.atomic.AtomicLong

/** State keys for legacy-only toggles. */
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

    // Keep one waiting nudge. Replace it when a newer repeat arrives.
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
                presetFeedback = null,
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
            // Legacy-only settings still need the socket on API firmware.
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
                        val socketMessage = reason?.takeIf(String::isNotBlank)?.let { text -> "Live state: $text" }
                        it.copy(message = it.message ?: socketMessage)
                    }
                }
            },
        )
    }

    private suspend fun fetchApiState(
        target: String,
        generation: Long,
        sequenceOnly: Boolean = false,
    ): DeviceState {
        val path = if (sequenceOnly) "/api/v1/state?sequenceOnly=1" else "/api/v1/state"
        val response = client.get(target, path)
        if (!response.successful) throw IOException("State request failed (HTTP ${response.code})")
        val state = ApiJsonParser.state(response.text())
        if (state.sequence == null || state.sequence !in 0..0xffff_ffffL) {
            throw IOException("State reply has no valid command sequence")
        }
        updateIfCurrent(target, generation) { it.copy(deviceState = it.deviceState.merge(state)) }
        return state
    }

    private suspend fun readApiState(target: String, generation: Long): DeviceState? = try {
        fetchApiState(target, generation)
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        null
    }

    private suspend fun refreshApiState(target: String, generation: Long): Boolean =
        readApiState(target, generation) != null

    /** Give loop() time to take its first picture sample. */
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

    private suspend fun refreshSavedSlot(connection: ActiveConnection, index: Int): Boolean {
        val response = client.get(connection.host, "/api/v1/presets?offset=$index&limit=1")
        if (!response.successful) return false
        val slot = ApiJsonParser.presets(response.text()).items.singleOrNull { it.index == index }
            ?: return false
        updateIfCurrent(connection) { current ->
            current.copy(presets = (current.presets.filterNot { it.index == index } + slot).sortedBy(PresetSlot::index))
        }
        return true
    }

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

    /** Don't send a legacy toggle when the setting already matches. */
    fun setToggle(stateKey: String, desired: Boolean, command: DeviceCommand) {
        if (mutableUiState.value.deviceState.option(stateKey) == desired) return
        execute(command) { it.withOption(stateKey, desired) }
    }

    fun loadPreset(slot: PresetSlot) {
        val connection = currentConnection() ?: return
        scope.launch {
            withCommandLock(connection, presetAction = "Load slot ${slot.slot}") {
                var result = if (connection.protocol == ProtocolMode.API_V1) {
                    // The API takes a slot character, not an index.
                    sendApiCommand(connection.host, "activate_preset", slot.slot.toString())
                } else {
                    val selected = client.get(connection.host, "/slot/set?slot=${slot.slot}").toCommandResult()
                    if (selected.ok) {
                        sendLegacyCommand(connection.host, ControlChannel.USER, '3')
                    } else {
                        selected
                    }
                }
                if (result.ok && isCurrent(connection)) {
                    if (connection.protocol == ProtocolMode.API_V1) {
                        if (!awaitApiSequence(connection, result.sequence, API_SLOW_CONFIRM_TIMEOUT_MS, slot.slot) &&
                            isCurrent(connection)
                        ) {
                            result = CommandResult(false, "preset_not_loaded", result.sequence)
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

    /** Saves the scaler snapshot first, then updates the slot's display metadata. */
    fun savePreset(slot: PresetSlot, name: String) {
        val connection = currentConnection() ?: return
        scope.launch {
            withCommandLock(
                connection,
                refreshPresets = true,
                presetAction = "Save slot ${slot.slot}",
                savedSlotIndex = slot.index,
            ) {
                val safeName = truncateUtf8(name.trim(), PRESET_NAME_MAX_BYTES)
                require(safeName.isNotBlank() && !safeName.equals("Empty", ignoreCase = true)) {
                    "Choose a preset name other than Empty"
                }
                val encoded = URLEncoder.encode(safeName, Charsets.UTF_8.name())
                val metadataPath = "/slot/save?index=${slot.index}&name=$encoded"

                if (connection.protocol == ProtocolMode.API_V1) {
                    // Older API builds need the legacy save sequence.
                    val result = sendApiCommand(connection.host, "save_preset", slot.slot.toString())
                    if (result.ok) {
                        if (!awaitApiSequence(connection, result.sequence, API_SLOW_CONFIRM_TIMEOUT_MS) &&
                            isCurrent(connection)
                        ) {
                            return@withCommandLock CommandResult(
                                false,
                                "confirmation_timeout",
                                result.sequence,
                            )
                        }
                        if (!isCurrent(connection)) {
                            return@withCommandLock CommandResult(false, "connection_changed")
                        }
                        val metadata = client.get(connection.host, metadataPath).toCommandResult()
                        if (!metadata.ok) {
                            throw IOException("Save command completed, but slot details failed: ${metadata.describe()}")
                        }
                        return@withCommandLock metadata
                    }
                    if (result.status != "invalid") return@withCommandLock result
                }

                savePresetLegacy(connection, slot, metadataPath)
            }
        }
    }

    private suspend fun savePresetLegacy(
        connection: ActiveConnection,
        slot: PresetSlot,
        metadataPath: String,
    ): CommandResult {
        val selected = client.get(connection.host, "/slot/set?slot=${slot.slot}").toCommandResult()
        if (!selected.ok) return selected

        val metadata = client.get(connection.host, metadataPath).toCommandResult()
        if (!metadata.ok) return metadata

        return sendLegacyCommand(connection.host, ControlChannel.USER, '4')
    }

    fun removePreset(slot: PresetSlot) {
        val connection = currentConnection() ?: return
        scope.launch {
            withCommandLock(connection, refreshPresets = true, presetAction = "Remove slot ${slot.slot}") {
                // Legacy removal takes two requests.
                val selected = client.get(connection.host, "/slot/set?slot=${slot.slot}").toCommandResult()
                if (!selected.ok) return@withCommandLock selected
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
        presetAction: String? = null,
        savedSlotIndex: Int? = null,
        block: suspend () -> CommandResult,
    ) {
        if (!isCurrent(connection)) return
        if (!commandMutex.tryLock()) {
            updateIfCurrent(connection) { it.copy(message = "A command is already in progress") }
            return
        }
        updateIfCurrent(connection) {
            it.copy(
                busy = true,
                message = null,
                presetFeedback = presetAction?.let { action -> "$action: working..." } ?: it.presetFeedback,
            )
        }
        try {
            val result = block()
            if (result.ok && refreshPresets && isCurrent(connection)) {
                val refreshed = if (connection.protocol == ProtocolMode.API_V1) {
                    if (savedSlotIndex != null) refreshSavedSlot(connection, savedSlotIndex)
                    else refreshApiPresets(connection.host, connection.generation)
                } else {
                    refreshLegacyPresets(connection.host, connection.generation)
                }
                if (!refreshed) throw IOException("Command completed, but the preset list could not be refreshed")
            }
            if (isCurrent(connection)) {
                val feedback = when {
                    !result.ok -> result.describe()
                    connection.protocol == ProtocolMode.LEGACY || result.status == "sent" ->
                        "Command sent; legacy firmware does not confirm completion"
                    else -> "Command completed"
                }
                updateIfCurrent(connection) {
                    it.copy(
                        message = if (!result.ok) feedback else it.message,
                        presetFeedback = presetAction?.let { action -> "$action: $feedback" } ?: it.presetFeedback,
                    )
                }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            val feedback = error.message ?: "Command failed"
            updateIfCurrent(connection) {
                it.copy(
                    message = feedback,
                    presetFeedback = presetAction?.let { action -> "$action: $feedback" } ?: it.presetFeedback,
                )
            }
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
        val result = client.get(target, "$route?$key").toCommandResult()
        return if (result.ok) result.copy(status = "sent") else result
    }

    private suspend fun processIncrementalCommands() {
        for (request in incrementalCommands) {
            val connection = request.connection
            if (!isCurrent(connection) || !commandMutex.tryLock()) continue
            try {
                var result = send(request.command, connection)
                if (result.busy && isCurrent(connection)) {
                    // The previous nudge may still be running. Retry busy once.
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
        loadedSlot: Char? = null,
    ): Boolean {
        // Legacy-only commands have no sequence, even on API firmware.
        if (targetSequence == null) {
            return refreshApiState(connection.host, connection.generation)
        }

        val deadline = System.nanoTime() + timeoutMs * NANOS_PER_MILLISECOND
        var pollAttempt = 0
        var sequenceReached = false
        var lastError: Exception? = null
        while (true) {
            if (!isCurrent(connection)) return false
            val remainingNanos = deadline - System.nanoTime()
            if (remainingNanos <= 0) break
            val delayMs = minOf(
                confirmationPollDelayMs(pollAttempt++),
                (remainingNanos / NANOS_PER_MILLISECOND).coerceAtLeast(1L),
            )
            delay(delayMs)
            if (System.nanoTime() >= deadline) break
            if (!isCurrent(connection)) return false
            try {
                var polledState: DeviceState? = null
                // RC6 ignores sequenceOnly and returns full state; newer firmware skips picture reads.
                if (!sequenceReached) {
                    val state = fetchApiState(connection.host, connection.generation, sequenceOnly = true)
                    polledState = state
                    sequenceReached = state.sequence?.let { sequenceHasReached(it, targetSequence) } == true
                }
                if (sequenceReached) {
                    val state = polledState?.takeIf { it.presetCode != null }
                        ?: fetchApiState(connection.host, connection.generation)
                    if (!isCurrent(connection)) return false
                    // RGBHV loads finish later in the sync watcher. Don't confirm a built-in fallback.
                    if (loadedSlot == null || (state.slot == loadedSlot && state.presetCode == '9')) return true
                }
                lastError = null
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                lastError = error
            }
        }
        lastError?.let { throw IOException("Could not check command completion: ${it.message}", it) }
        return false
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

    /** Legacy success replies are empty or 'true'. */
    private fun HttpResult.toCommandResult(): CommandResult {
        if (!successful) return CommandResult(false, "http_$code")
        val payload = text().trim()
        if (payload.equals("false", ignoreCase = true)) return CommandResult(false, "rejected")
        if (payload.isNotEmpty() && !payload.equals("true", ignoreCase = true)) {
            return CommandResult(false, "invalid_response")
        }
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

        // Allow one 500 ms picture-sampling interval.
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
    status == "preset_not_loaded" -> "The selected preset was not confirmed as loaded. Check the saved slot and input signal."
    status == "invalid_response" -> "The device returned an invalid command reply"
    status.startsWith("http_") -> "Device request failed (HTTP ${status.removePrefix("http_")})"
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

/** Keep fields that the other transport doesn't report. */
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
    clockGeneratorDetected = new.clockGeneratorDetected ?: clockGeneratorDetected,
    clockWiring = new.clockWiring ?: clockWiring,
    pictureValid = new.pictureValid ?: pictureValid,
    brightness = new.brightness ?: brightness,
    contrast = new.contrast ?: contrast,
    pbGain = new.pbGain ?: pbGain,
    prGain = new.prGain ?: prGain,
    adcGain = new.adcGain ?: adcGain,
    freeHeap = new.freeHeap ?: freeHeap,
)
