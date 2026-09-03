package com.gbscontrol.app

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch

/**
 * One output option. [apiValue] is the value `set_resolution` expects, or null for the pass-through
 * mode, which API v1 does not cover and which therefore always takes the legacy route.
 */
data class ResolutionOption(
    val label: String,
    val apiValue: String?,
    val command: Char,
    val channel: ControlChannel,
)

enum class PictureAxis(val label: String) { MOVE("Move"), SCALE("Scale"), BORDERS("Borders") }

enum class PictureDirection { UP, DOWN, LEFT, RIGHT }

class GbsViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = GbsRepository(application, viewModelScope)
    val uiState = repository.uiState

    val resolutions = listOf(
        ResolutionOption("1920×1080", "1920x1080", 's', ControlChannel.USER),
        ResolutionOption("1280×1024", "1280x1024", 'p', ControlChannel.USER),
        ResolutionOption("1280×960", "1280x960", 'f', ControlChannel.USER),
        ResolutionOption("1280×720", "1280x720", 'g', ControlChannel.USER),
        ResolutionOption("480p / 576p", "480p", 'h', ControlChannel.USER),
        ResolutionOption("Downscale", "downscale", 'L', ControlChannel.USER),
        ResolutionOption("Pass-through", null, 'K', ControlChannel.ACTION),
    )

    fun connect(host: String) = viewModelScope.launch { repository.connect(host) }
    fun refresh() = repository.refresh()
    fun forgetHost(host: String) = repository.forgetHost(host)
    fun clearMessage() = repository.clearMessage()

    fun selectResolution(option: ResolutionOption) = repository.execute(
        DeviceCommand(
            apiName = option.apiValue?.let { "set_resolution" },
            apiValue = option.apiValue,
            legacyCommand = option.command,
            legacyChannel = option.channel,
        ),
        confirmation = CommandConfirmation.SLOW,
    ) { it.copy(preset = option.label) }

    // The nine toggles API v1 implements. The repository compares against known state first, so a
    // switch already in the requested position sends nothing at all.
    fun setScanlines(on: Boolean) = toggle("set_scanlines", on, '7', ControlChannel.USER)
    fun setLineFilter(on: Boolean) = toggle("set_line_filter", on, 'm', ControlChannel.USER)
    fun setPeaking(on: Boolean) = toggle("set_peaking", on, 'f', ControlChannel.ACTION)
    fun setStepResponse(on: Boolean) = toggle("set_step_response", on, 'V', ControlChannel.ACTION)
    fun setAutoGain(on: Boolean) = toggle("set_auto_gain", on, 'T', ControlChannel.ACTION)
    fun setFrameTimeLock(on: Boolean) = toggle("set_frame_time_lock", on, '5', ControlChannel.USER)
    fun setOutputComponent(on: Boolean) = toggle("set_output_component", on, 'L', ControlChannel.ACTION)
    fun setFullHeight(on: Boolean) = toggle("set_full_height", on, 'v', ControlChannel.USER)
    fun setMatchedPresets(on: Boolean) = toggle("set_matched_presets", on, 'Z', ControlChannel.ACTION)

    // Legacy-only toggles: not part of API v1, so they use /uc on every firmware. Their state still
    // arrives on the WebSocket frame, which API v1 firmware also keeps serving.
    fun setPalForce60(on: Boolean) = toggle(null, on, '0', ControlChannel.USER, StateKeys.PAL_FORCE_60)
    fun setPreferScalingRgbhv(on: Boolean) = toggle(null, on, 'x', ControlChannel.USER, StateKeys.SCALING_RGBHV)
    fun setCalibrationAdc(on: Boolean) = toggle(null, on, 'w', ControlChannel.USER, StateKeys.CALIBRATION_ADC)
    fun setExternalClockDisabled(on: Boolean) = toggle(null, on, 'X', ControlChannel.USER, StateKeys.EXT_CLOCK_OFF)

    private fun toggle(
        apiName: String?,
        enabled: Boolean,
        command: Char,
        channel: ControlChannel,
        stateKey: String = apiName.orEmpty(),
    ) = repository.setToggle(stateKey, enabled, DeviceCommand(apiName, enabled.toString(), command, channel))

    fun setDeinterlace(displayMode: String) {
        val bob = displayMode.equals(DeinterlaceModes.BOB, ignoreCase = true)
        repository.execute(
            DeviceCommand(
                apiName = "set_deinterlace",
                apiValue = DeinterlaceModes.apiValue(displayMode),
                legacyCommand = if (bob) 'q' else 'r',
                legacyChannel = ControlChannel.USER,
            )
        ) { it.copy(deinterlaceMode = if (bob) DeinterlaceModes.BOB else DeinterlaceModes.MOTION_ADAPTIVE) }
    }

    /** Cycles the scanline strength down in steps of 0x10, wrapping at 0x50, as the firmware does. */
    fun cycleScanlineIntensity() = run("scanline_strength", 'K', ControlChannel.USER, incremental = true)

    /** No API v1 equivalent; the alternate frame-lock method is a legacy-only action. */
    fun switchFrameLockMethod() = run(null, 'i', ControlChannel.USER)

    fun adjustGain(increase: Boolean) =
        run(
            if (increase) "adc_gain_plus" else "adc_gain_minus",
            if (increase) 'n' else 'o',
            ControlChannel.USER,
            incremental = true,
        )

    fun resetColorDefaults() = run("color_defaults", 'U', ControlChannel.USER)

    fun adjustPicture(axis: PictureAxis, direction: PictureDirection) {
        val (name, command, channel) = when (axis) {
            PictureAxis.MOVE -> when (direction) {
                PictureDirection.LEFT -> Triple("move_left", '7', ControlChannel.ACTION)
                PictureDirection.UP -> Triple("move_up", '*', ControlChannel.ACTION)
                PictureDirection.RIGHT -> Triple("move_right", '6', ControlChannel.ACTION)
                PictureDirection.DOWN -> Triple("move_down", '/', ControlChannel.ACTION)
            }
            PictureAxis.SCALE -> when (direction) {
                PictureDirection.LEFT -> Triple("scale_h_minus", 'h', ControlChannel.ACTION)
                PictureDirection.UP -> Triple("scale_v_plus", '4', ControlChannel.ACTION)
                PictureDirection.RIGHT -> Triple("scale_h_plus", 'z', ControlChannel.ACTION)
                PictureDirection.DOWN -> Triple("scale_v_minus", '5', ControlChannel.ACTION)
            }
            PictureAxis.BORDERS -> when (direction) {
                PictureDirection.LEFT -> Triple("border_left", 'B', ControlChannel.USER)
                PictureDirection.UP -> Triple("border_up", 'C', ControlChannel.USER)
                PictureDirection.RIGHT -> Triple("border_right", 'A', ControlChannel.USER)
                PictureDirection.DOWN -> Triple("border_down", 'D', ControlChannel.USER)
            }
        }
        run(name, command, channel, incremental = true)
    }

    /**
     * Colour nudges. The scaler exposes no absolute writes over either transport, so these step by
     * one and the resulting register value is read back from `GET /api/v1/state`.
     */
    fun adjustColor(channelName: String, increase: Boolean) {
        val (name, command) = when (channelName) {
            "brightness" -> if (increase) "brightness_plus" to 'Z' else "brightness_minus" to 'T'
            "contrast" -> if (increase) "contrast_plus" to 'N' else "contrast_minus" to 'M'
            "pb_gain" -> if (increase) "pb_gain_plus" to 'Q' else "pb_gain_minus" to 'H'
            "pr_gain" -> if (increase) "pr_gain_plus" to 'P' else "pr_gain_minus" to 'S'
            else -> return
        }
        run(name, command, ControlChannel.USER, incremental = true)
    }

    private fun run(
        apiName: String?,
        command: Char,
        channel: ControlChannel,
        incremental: Boolean = false,
    ) = repository.execute(
        DeviceCommand(apiName, null, command, channel),
        confirmation = if (incremental) CommandConfirmation.INCREMENTAL else CommandConfirmation.STANDARD,
    )

    fun loadPreset(slot: PresetSlot) = repository.loadPreset(slot)
    fun savePreset(slot: PresetSlot, name: String) = repository.savePreset(slot, name)
    fun removePreset(slot: PresetSlot) = repository.removePreset(slot)

    override fun onCleared() {
        repository.close()
        super.onCleared()
    }
}
