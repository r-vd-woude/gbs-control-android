package com.gbscontrol.app

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val AppUiState.controlsBlocked: Boolean
    get() = busy || status != ConnectionStatus.CONNECTED

@Composable
fun DevicesScreen(state: AppUiState, viewModel: GbsViewModel) {
    var address by rememberSaveable(state.host) { mutableStateOf(state.host) }
    val connectionChangeEnabled = !state.busy && state.status != ConnectionStatus.CONNECTING
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            SectionTitle("Connect to a device", "Discovery works on Wi-Fi without requiring Internet access.")
            OutlinedTextField(
                value = address,
                onValueChange = { address = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("Hostname or IP address") },
                placeholder = { Text("gbscontrol.local") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            )
            Spacer(Modifier.height(8.dp))
            Button(onClick = { viewModel.connect(address) }, enabled = connectionChangeEnabled) { Text("Connect") }
        }

        if (state.discoveredDevices.isNotEmpty()) {
            item { Text("Discovered", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold) }
            items(state.discoveredDevices, key = { "found-${it.host}" }) { device ->
                DeviceCard(
                    title = device.name,
                    subtitle = buildString {
                        append(device.host)
                        device.apiVersion?.let { append(" · API v$it") }
                    },
                    connected = device.host == state.host && state.status == ConnectionStatus.CONNECTED,
                    enabled = connectionChangeEnabled,
                    onConnect = { viewModel.connect(device.host) },
                )
            }
        }

        item { Text("Remembered", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold) }
        items(state.rememberedHosts, key = { "saved-$it" }) { host ->
            DeviceCard(
                title = host,
                subtitle = if (host == AppPrefs.DEFAULT_HOST) "Default mDNS address" else "Saved device",
                connected = host == state.host && state.status == ConnectionStatus.CONNECTED,
                enabled = connectionChangeEnabled,
                onConnect = { viewModel.connect(host) },
                onForget = if (host != state.host && host != AppPrefs.DEFAULT_HOST) ({ viewModel.forgetHost(host) }) else null,
            )
        }
    }
}

@Composable
private fun DeviceCard(
    title: String,
    subtitle: String,
    connected: Boolean,
    enabled: Boolean,
    onConnect: () -> Unit,
    onForget: (() -> Unit)? = null,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(subtitle, style = MaterialTheme.typography.bodySmall)
            }
            if (onForget != null) TextButton(onClick = onForget, enabled = enabled) { Text("Forget") }
            Button(onClick = onConnect, enabled = enabled && !connected) {
                Text(if (connected) "Connected" else "Connect")
            }
        }
    }
}

@Composable
fun HomeScreen(state: AppUiState, viewModel: GbsViewModel) {
    val device = state.deviceState
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = when (state.status) {
                    ConnectionStatus.CONNECTED -> MaterialTheme.colorScheme.primaryContainer
                    ConnectionStatus.ERROR -> MaterialTheme.colorScheme.errorContainer
                    else -> MaterialTheme.colorScheme.surfaceVariant
                }
            ),
        ) {
            Column(Modifier.padding(16.dp)) {
                Text(state.status.name.lowercase().replaceFirstChar(Char::uppercase), style = MaterialTheme.typography.titleLarge)
                Text(
                    when (state.protocol) {
                        ProtocolMode.API_V1 -> "Native API v${state.deviceInfo?.apiVersion ?: 1}"
                        ProtocolMode.LEGACY -> "Legacy compatibility mode"
                        ProtocolMode.UNKNOWN -> "Detecting firmware capabilities"
                    }
                )
                state.deviceInfo?.firmwareVersion?.let { Text("Firmware $it") }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = viewModel::refresh,
                    enabled = !state.busy && state.status != ConnectionStatus.CONNECTING,
                ) { Text("Refresh") }
            }
        }

        SectionTitle("Live state")
        InfoRow("Signal", device.signalPresent?.let { if (it) "Present" else "No signal" } ?: "Not reported")
        InfoRow("Input", device.inputMode ?: "Not reported")
        InfoRow("Output", device.preset ?: "Waiting for state")
        InfoRow("Preset slot", device.slot?.toString() ?: "None")
        NativeToggle("Scanlines", device.scanlines, state.controlsBlocked, viewModel::setScanlines)

        SectionTitle("Output resolution")
        viewModel.resolutions.chunked(2).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { option ->
                    OutlinedButton(
                        onClick = { viewModel.selectResolution(option) },
                        enabled = !state.controlsBlocked,
                        modifier = Modifier.weight(1f),
                    ) { Text(option.label) }
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PresetsScreen(state: AppUiState, viewModel: GbsViewModel) {
    var populatedOnly by rememberSaveable { mutableStateOf(true) }
    var saveSlot by remember { mutableStateOf<PresetSlot?>(null) }
    var removeSlot by remember { mutableStateOf<PresetSlot?>(null) }
    val visible = if (populatedOnly) state.presets.filterNot(PresetSlot::empty) else state.presets

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        stickyHeader {
            Surface {
                Column(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
                    SectionTitle("Preset slots", "${state.presets.count { !it.empty }} populated of ${state.presets.size}")
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Switch(checked = populatedOnly, onCheckedChange = { populatedOnly = it })
                        Spacer(Modifier.width(8.dp))
                        Text("Show populated only")
                        Spacer(Modifier.weight(1f))
                        TextButton(
                            onClick = viewModel::refresh,
                            enabled = !state.busy && state.status != ConnectionStatus.CONNECTING,
                        ) { Text("Refresh") }
                    }
                }
            }
        }
        if (visible.isEmpty()) {
            item { Text(if (state.presets.isEmpty()) "Connect to load presets." else "No populated preset slots.") }
        }
        items(visible, key = PresetSlot::index) { slot ->
            PresetCard(
                slot = slot,
                selected = state.deviceState.slot == slot.slot,
                busy = state.controlsBlocked,
                onLoad = { viewModel.loadPreset(slot) },
                onSave = { saveSlot = slot },
                onRemove = { removeSlot = slot },
            )
        }
    }

    saveSlot?.let { slot ->
        NamePresetDialog(slot, onDismiss = { saveSlot = null }) { name ->
            viewModel.savePreset(slot, name)
            saveSlot = null
        }
    }
    removeSlot?.let { slot ->
        AlertDialog(
            onDismissRequest = { removeSlot = null },
            title = { Text("Remove ${slot.name}?") },
            text = { Text("This clears slot ${slot.index + 1} on the GBS device.") },
            confirmButton = {
                TextButton(onClick = { viewModel.removePreset(slot); removeSlot = null }) { Text("Remove") }
            },
            dismissButton = { TextButton(onClick = { removeSlot = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun PresetCard(
    slot: PresetSlot,
    selected: Boolean,
    busy: Boolean,
    onLoad: () -> Unit,
    onSave: () -> Unit,
    onRemove: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = if (selected) CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
        else CardDefaults.cardColors(),
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${slot.index + 1}", style = MaterialTheme.typography.labelLarge, modifier = Modifier.width(36.dp))
                Column(Modifier.weight(1f)) {
                    Text(slot.name, fontWeight = FontWeight.SemiBold)
                    Text(
                        buildString {
                            append("slot ${slot.slot}")
                            slot.presetId?.let { append(" · preset $it") }
                            slot.scanlineStrength?.let { append(" · scanlines $it") }
                        },
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                if (selected) AssistChip(onClick = {}, label = { Text("Active") })
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onLoad, enabled = !busy && !slot.empty) { Text("Load") }
                TextButton(onClick = onSave, enabled = !busy) { Text(if (slot.empty) "Save" else "Overwrite") }
                TextButton(onClick = onRemove, enabled = !busy && !slot.empty) { Text("Remove") }
            }
        }
    }
}

@Composable
private fun NamePresetDialog(slot: PresetSlot, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var name by remember(slot.index) { mutableStateOf(if (slot.empty) "Preset ${slot.index + 1}" else slot.name) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Save preset to slot ${slot.index + 1}") },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it.take(24) },
                label = { Text("Preset name") },
                singleLine = true,
            )
        },
        confirmButton = { TextButton(onClick = { onSave(name.trim()) }, enabled = name.isNotBlank()) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun PictureScreen(state: AppUiState, viewModel: GbsViewModel) {
    var axis by rememberSaveable { mutableStateOf(PictureAxis.MOVE) }
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        SectionTitle("Picture control", "Hold an arrow for repeated adjustment.")
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PictureAxis.entries.forEach { choice ->
                FilterChip(
                    selected = axis == choice,
                    onClick = { axis = choice },
                    label = { Text(choice.label) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
        DirectionPad(enabled = !state.controlsBlocked) { direction -> viewModel.adjustPicture(axis, direction) }

        Divider()
        SectionTitle("ADC gain", state.deviceState.adcGain?.let { "Register 0x%02X".format(it) })
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            RepeatButton("− Gain", !state.controlsBlocked) { viewModel.adjustGain(false) }
            RepeatButton("+ Gain", !state.controlsBlocked) { viewModel.adjustGain(true) }
        }
        NativeToggle("Automatic gain", state.deviceState.autoGain, state.controlsBlocked, viewModel::setAutoGain)

        Divider()
        SectionTitle(
            "Colour",
            // The scaler has no absolute register writes, so every colour control steps by one.
            // On API v1 the resulting value is read back and shown; legacy firmware cannot report it.
            if (state.protocol == ProtocolMode.API_V1) "Values are read back from the device."
            else "Legacy firmware cannot report the current values.",
        )
        ColorControl("Brightness", "brightness", state.deviceState.brightness, state.controlsBlocked, viewModel)
        ColorControl("Contrast", "contrast", state.deviceState.contrast, state.controlsBlocked, viewModel)
        ColorControl("Pb / U gain", "pb_gain", state.deviceState.pbGain, state.controlsBlocked, viewModel)
        ColorControl("Pr / V gain", "pr_gain", state.deviceState.prGain, state.controlsBlocked, viewModel)
        OutlinedButton(
            onClick = viewModel::resetColorDefaults,
            enabled = !state.controlsBlocked,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Restore colour defaults") }
    }
}

@Composable
private fun DirectionPad(enabled: Boolean, onDirection: (PictureDirection) -> Unit) {
    // Three equal-width cells per row: the vertical arrows land in the middle cell and so end up
    // the same size as the horizontal pair instead of shrinking to fit their glyph.
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Spacer(Modifier.weight(1f))
            RepeatButton("↑", enabled) { onDirection(PictureDirection.UP) }
            Spacer(Modifier.weight(1f))
        }
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RepeatButton("←", enabled) { onDirection(PictureDirection.LEFT) }
            Box(Modifier.weight(1f).height(52.dp), contentAlignment = Alignment.Center) { Text("•") }
            RepeatButton("→", enabled) { onDirection(PictureDirection.RIGHT) }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Spacer(Modifier.weight(1f))
            RepeatButton("↓", enabled) { onDirection(PictureDirection.DOWN) }
            Spacer(Modifier.weight(1f))
        }
    }
}

@Composable
private fun RowScope.RepeatButton(label: String, enabled: Boolean, onRepeat: () -> Unit) {
    RepeatButton(label, enabled, Modifier.weight(1f), onRepeat)
}

@Composable
private fun RepeatButton(label: String, enabled: Boolean, modifier: Modifier = Modifier, onRepeat: () -> Unit) {
    // The gesture scope is not a coroutine scope, so the repeat loop is hosted by the composition
    // and cancelled the moment the finger lifts.
    val scope = rememberCoroutineScope()
    Surface(
        modifier = modifier
            .height(52.dp)
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                detectTapGestures(
                    onPress = {
                        val repeating = scope.launch {
                            onRepeat()
                            delay(REPEAT_INITIAL_DELAY_MS)
                            while (true) {
                                onRepeat()
                                delay(REPEAT_INTERVAL_MS)
                            }
                        }
                        tryAwaitRelease()
                        repeating.cancel()
                    }
                )
            },
        shape = RoundedCornerShape(24.dp),
        color = if (enabled) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceVariant,
    ) { Box(contentAlignment = Alignment.Center) { Text(label, fontWeight = FontWeight.SemiBold) } }
}

@Composable
private fun ColorControl(
    label: String,
    name: String,
    value: Int?,
    busy: Boolean,
    viewModel: GbsViewModel,
) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label)
            Text(value?.toString() ?: "—", style = MaterialTheme.typography.labelSmall)
        }
        RepeatButton("−", !busy, Modifier.width(72.dp)) { viewModel.adjustColor(name, false) }
        Spacer(Modifier.width(8.dp))
        RepeatButton("+", !busy, Modifier.width(72.dp)) { viewModel.adjustColor(name, true) }
    }
}

@Composable
fun FiltersScreen(state: AppUiState, viewModel: GbsViewModel) {
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        SectionTitle("Filters", "Values are synchronized from the device over the live-state connection.")
        NativeToggle("Scanlines", state.deviceState.scanlines, state.controlsBlocked, viewModel::setScanlines)
        ListAction(
            "Scanline intensity",
            state.deviceState.scanlineStrength?.toString() ?: "Cycle legacy strength",
            enabled = !state.controlsBlocked,
        ) {
            viewModel.cycleScanlineIntensity()
        }
        NativeToggle("Line filter", state.deviceState.lineFilter, state.controlsBlocked, viewModel::setLineFilter)
        NativeToggle("Peaking", state.deviceState.peaking, state.controlsBlocked, viewModel::setPeaking)
        NativeToggle("Step response", state.deviceState.stepResponse, state.controlsBlocked, viewModel::setStepResponse)
        Divider(Modifier.padding(vertical = 8.dp))
        Text("Deinterlace", style = MaterialTheme.typography.titleMedium)
        listOf(DeinterlaceModes.MOTION_ADAPTIVE, DeinterlaceModes.BOB).forEach { mode ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(
                    selected = state.deviceState.deinterlaceMode.equals(mode, ignoreCase = true),
                    onClick = { viewModel.setDeinterlace(mode) },
                    enabled = !state.controlsBlocked,
                )
                Text(mode)
            }
        }
    }
}

@Composable
fun SettingsScreen(state: AppUiState, viewModel: GbsViewModel, onLegacy: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        SectionTitle("Device settings")
        NativeToggle("Matched presets", state.deviceState.matchedPresets, state.controlsBlocked, viewModel::setMatchedPresets)
        NativeToggle("Full height", state.deviceState.fullHeight, state.controlsBlocked, viewModel::setFullHeight)
        NativeToggle("Low-res upscaling", state.deviceState.preferScalingRgbhv, state.controlsBlocked, viewModel::setPreferScalingRgbhv)
        NativeToggle("YPbPr component output", state.deviceState.outputComponent, state.controlsBlocked, viewModel::setOutputComponent)
        NativeToggle("Force PAL 50 Hz to 60 Hz", state.deviceState.palForce60, state.controlsBlocked, viewModel::setPalForce60)
        NativeToggle("Disable external clock", state.deviceState.externalClockDisabled, state.controlsBlocked, viewModel::setExternalClockDisabled)
        NativeToggle("ADC calibration", state.deviceState.calibrationAdc, state.controlsBlocked, viewModel::setCalibrationAdc)
        NativeToggle("Frame-time lock", state.deviceState.frameTimeLock, state.controlsBlocked, viewModel::setFrameTimeLock)
        ListAction(
            "Switch frame-lock method",
            "Try the alternate method if the display shifts",
            enabled = !state.controlsBlocked,
        ) {
            viewModel.switchFrameLockMethod()
        }

        Divider(Modifier.padding(vertical = 12.dp))
        SectionTitle("Device information")
        InfoRow("Address", state.host)
        InfoRow("Protocol", state.protocol.name.replace('_', ' '))
        state.deviceInfo?.firmwareVersion?.let { InfoRow("Firmware", it) }
        state.deviceInfo?.deviceId?.let { InfoRow("Device ID", it) }
        state.deviceInfo?.capabilities?.takeIf { it.isNotEmpty() }?.let {
            InfoRow("Capabilities", it.sorted().joinToString())
        }
        state.deviceState.clockGeneratorDetected?.let {
            InfoRow("Clock generator", if (it) "Detected" else "Not detected")
        }
        state.deviceState.clockWiring?.let { InfoRow("Clock wiring", clockWiringLabel(it)) }

        Divider(Modifier.padding(vertical = 12.dp))
        SectionTitle("Advanced", "Use the original firmware interface for Wi-Fi, backup, restore and firmware update.")
        OutlinedButton(onClick = onLegacy, modifier = Modifier.fillMaxWidth()) { Text("Open legacy web interface") }
    }
}

@Composable
private fun NativeToggle(label: String, value: Boolean?, busy: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label)
            if (value == null) Text("Waiting for device state", style = MaterialTheme.typography.labelSmall)
        }
        Switch(checked = value ?: false, onCheckedChange = onChange, enabled = value != null && !busy)
    }
}

@Composable
private fun ListAction(label: String, detail: String, enabled: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label)
            Text(detail, style = MaterialTheme.typography.labelSmall)
        }
        OutlinedButton(onClick = onClick, enabled = enabled) { Text("Run") }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.Top) {
        Text(label, modifier = Modifier.width(110.dp), style = MaterialTheme.typography.labelLarge)
        Text(value, modifier = Modifier.weight(1f))
    }
}

private fun clockWiringLabel(wiring: String): String = when (wiring) {
    "standard" -> "Standard"
    "mcbazel" -> "McBazel reversed"
    "unknown" -> "Unknown"
    else -> wiring
}

@Composable
private fun SectionTitle(title: String, supporting: String? = null) {
    Column {
        Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
        supporting?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    }
}

/** Press-and-hold pacing for the directional and colour controls. */
private const val REPEAT_INITIAL_DELAY_MS = 350L
private const val REPEAT_INTERVAL_MS = 110L
