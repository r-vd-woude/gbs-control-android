package com.gbscontrol.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The fixtures below are the documents the firmware actually emits, copied from the "HTTP API v1"
 * section of the firmware README. If the firmware contract changes, these tests are the first
 * thing that should fail.
 */
class ApiJsonParserTest {

    private val deviceJson = """
        {"apiVersion":1,"firmwareVersion":"v2.3.0-rc.1","deviceId":"esp8266-abcdef",
         "hostname":"gbscontrol","slots":72,"maxPageSize":8,
         "capabilities":["state","presets","commands","legacy"]}
    """.trimIndent()

    private val stateJson = """
        {"sequence":12,"preset":3,"slot":"A","signalPresent":true,"inputMode":1,"scanlines":true,
         "scanlineStrength":48,"lineFilter":true,"peaking":true,"stepResponse":true,
         "autoGain":false,"frameTimeLock":true,"deinterlaceMode":"motion_adaptive",
         "outputComponent":false,"fullHeight":true,"matchedPresets":false,"palForce60":false,
         "tap6":false,"scalingRgbhv":false,"externalClockGen":true,
         "clockGeneratorDetected":true,"clockWiring":"mcbazel","customPreset":false,
         "picture":{"valid":true,"brightness":0,"contrast":128,"pbGain":28,"prGain":41,
                    "adcGain":57,"hScale":512,"vScale":512},
         "memory":{"heap":31000,"heapMin":26000,"maxBlock":18000,"heapFrag":11,"uptime":384}}
    """.trimIndent()

    @Test
    fun `reads the device document`() {
        val device = ApiJsonParser.device(deviceJson)
        assertEquals(1, device.apiVersion)
        assertEquals("v2.3.0-rc.1", device.firmwareVersion)
        assertEquals("esp8266-abcdef", device.deviceId)
        assertEquals("gbscontrol", device.hostname)
        assertEquals(72, device.slots)
        assertEquals(8, device.maxPageSize)
        assertEquals(setOf("state", "presets", "commands", "legacy"), device.capabilities)
    }

    @Test
    fun `falls back to safe paging defaults on an older device document`() {
        val device = ApiJsonParser.device("""{"apiVersion":1,"hostname":"gbscontrol"}""")
        assertEquals(ApiJsonParser.DEFAULT_PAGE_SIZE, device.maxPageSize)
        assertEquals(LegacySlotParser.SLOT_COUNT, device.slots)
        assertNull(device.firmwareVersion)
    }

    @Test
    fun `reports apiVersion zero when the field is missing, so detection falls back to legacy`() {
        assertEquals(0, ApiJsonParser.device("""{"hostname":"gbscontrol"}""").apiVersion)
    }

    @Test
    fun `maps the numeric preset onto the same label as the websocket frame`() {
        val state = ApiJsonParser.state(stateJson)
        assertEquals("1280x720", state.preset)
        assertEquals('3', state.presetCode)
        assertEquals(LegacyStateParser.parse("#3A@@@")!!.preset, state.preset)
    }

    @Test
    fun `maps the numeric input mode onto a label`() {
        assertEquals("NTSC 240p / 480i", ApiJsonParser.state(stateJson).inputMode)
    }

    @Test
    fun `reads the flat state fields`() {
        val state = ApiJsonParser.state(stateJson)
        assertEquals(12L, state.sequence)
        assertEquals('A', state.slot)
        assertTrue(state.signalPresent!!)
        assertTrue(state.scanlines!!)
        assertEquals(48, state.scanlineStrength)
        assertTrue(state.lineFilter!!)
        assertTrue(state.peaking!!)
        assertTrue(state.stepResponse!!)
        assertFalse(state.autoGain!!)
        assertTrue(state.frameTimeLock!!)
        assertFalse(state.outputComponent!!)
        assertTrue(state.fullHeight!!)
        assertFalse(state.matchedPresets!!)
        assertEquals(31000, state.freeHeap)
        assertTrue(state.clockGeneratorDetected!!)
        assertEquals("mcbazel", state.clockWiring)
    }

    @Test
    fun `normalizes the deinterlace mode to the websocket spelling`() {
        assertEquals(DeinterlaceModes.MOTION_ADAPTIVE, ApiJsonParser.state(stateJson).deinterlaceMode)
        val bob = ApiJsonParser.state("""{"deinterlaceMode":"bob"}""")
        assertEquals(DeinterlaceModes.BOB, bob.deinterlaceMode)
        // Byte 4 bit 2 is the deinterlace flag; 'D' is 0x40 or 0x04.
        assertEquals(LegacyStateParser.parse("#3A@D@")!!.deinterlaceMode, bob.deinterlaceMode)
    }

    @Test
    fun `reads scalingRgbhv under the name the firmware uses`() {
        assertFalse(ApiJsonParser.state(stateJson).preferScalingRgbhv!!)
        assertTrue(ApiJsonParser.state("""{"scalingRgbhv":true}""").preferScalingRgbhv!!)
    }

    @Test
    fun `inverts externalClockGen, because the firmware reports the generator as enabled`() {
        assertFalse(ApiJsonParser.state(stateJson).externalClockDisabled!!)
        assertTrue(ApiJsonParser.state("""{"externalClockGen":false}""").externalClockDisabled!!)
    }

    @Test
    fun `leaves calibrationAdc to the websocket frame`() {
        assertNull(ApiJsonParser.state(stateJson).calibrationAdc)
    }

    @Test
    fun `reads the nested picture object`() {
        val state = ApiJsonParser.state(stateJson)
        assertTrue(state.pictureValid!!)
        assertEquals(0, state.brightness)
        assertEquals(128, state.contrast)
        assertEquals(28, state.pbGain)
        assertEquals(41, state.prGain)
        assertEquals(57, state.adcGain)
    }

    @Test
    fun `ignores picture registers the device has not sampled yet`() {
        val state = ApiJsonParser.state(
            """{"picture":{"valid":false,"brightness":0,"contrast":0,"pbGain":0,"prGain":0,"adcGain":0}}"""
        )
        assertFalse(state.pictureValid!!)
        assertNull(state.brightness)
        assertNull(state.contrast)
        assertNull(state.adcGain)
    }

    @Test
    fun `leaves absent state fields null so a websocket value survives the merge`() {
        val partial = ApiJsonParser.state("""{"sequence":4}""")
        assertNull(partial.scanlines)
        assertNull(partial.preset)
        val merged = LegacyStateParser.parse("#3AB@@")!!.merge(partial)
        assertEquals("1280x720", merged.preset)
        assertTrue(merged.scanlines!!)
        assertEquals(4L, merged.sequence)
    }

    @Test
    fun `reads a preset page`() {
        val page = ApiJsonParser.presets(
            """{"offset":0,"limit":8,"total":72,"items":[
                 {"index":0,"slot":"A","name":"My preset","presetId":3,"populated":true,
                  "scanlines":true,"scanlineStrength":48},
                 {"index":1,"slot":"B","name":"","presetId":0,"populated":false,
                  "scanlines":false,"scanlineStrength":0}]}"""
        )
        assertEquals(0, page.offset)
        assertEquals(72, page.total)
        assertEquals(2, page.items.size)

        val first = page.items[0]
        assertEquals("My preset", first.name)
        assertEquals('A', first.slot)
        assertEquals(3, first.presetId)
        assertFalse(first.empty)
        assertEquals(48, first.scanlineStrength)

        val second = page.items[1]
        assertTrue(second.empty)
        assertEquals("Slot 2", second.name)
    }

    @Test
    fun `trusts populated over the placeholder name`() {
        val page = ApiJsonParser.presets(
            """{"offset":0,"total":72,"items":[{"index":5,"slot":"F","name":"Empty","populated":true}]}"""
        )
        assertFalse(page.items.single().empty)
        assertEquals("Empty", page.items.single().name)
    }

    @Test
    fun `falls back to the name when populated is absent`() {
        val page = ApiJsonParser.presets(
            """{"offset":0,"total":72,"items":[{"index":5,"slot":"F","name":"Empty"}]}"""
        )
        assertTrue(page.items.single().empty)
    }

    @Test
    fun `survives a page with no items`() {
        val page = ApiJsonParser.presets("""{"offset":64,"limit":8,"total":72,"items":[]}""")
        assertEquals(64, page.offset)
        assertTrue(page.items.isEmpty())
    }

    @Test
    fun `derives index and slot when an item omits them`() {
        val page = ApiJsonParser.presets("""{"offset":2,"total":72,"items":[{"name":"Third"}]}""")
        val slot = page.items.single()
        assertEquals(2, slot.index)
        assertEquals('C', slot.slot)
    }

    @Test
    fun `reads an accepted command`() {
        val result = ApiJsonParser.commandResult(
            200, """{"ok":true,"status":"accepted","name":"set_scanlines","sequence":13}"""
        )
        assertTrue(result.ok)
        assertEquals("accepted", result.status)
        assertEquals(13L, result.sequence)
        assertFalse(result.busy)
    }

    @Test
    fun `treats a no-op as success, because the device is already in the requested state`() {
        val result = ApiJsonParser.commandResult(200, """{"ok":true,"status":"noop","name":"set_peaking"}""")
        assertTrue(result.ok)
        assertEquals("noop", result.status)
    }

    @Test
    fun `recognizes a busy command channel`() {
        val result = ApiJsonParser.commandResult(409, """{"ok":false,"status":"busy","name":"move_left"}""")
        assertFalse(result.ok)
        assertTrue(result.busy)
    }

    @Test
    fun `recognizes low memory`() {
        val result = ApiJsonParser.commandResult(503, """{"ok":false,"status":"low_memory","name":""}""")
        assertFalse(result.ok)
        assertTrue(result.lowMemory)
    }

    @Test
    fun `recognizes a rejected command`() {
        val result = ApiJsonParser.commandResult(400, """{"ok":false,"status":"invalid","name":"nope"}""")
        assertFalse(result.ok)
        assertEquals("invalid", result.status)
    }

    @Test
    fun `falls back to the status code for a non-JSON body`() {
        assertTrue(ApiJsonParser.commandResult(200, "").ok)
        assertFalse(ApiJsonParser.commandResult(500, "internal error").ok)
    }
}
