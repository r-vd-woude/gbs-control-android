package com.gbscontrol.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Fixtures for the 32-byte `SlotMeta` record the firmware writes to `/slots.bin`. */
private fun slotRecord(
    name: String,
    presetId: Int = 3,
    scanlines: Boolean = false,
    scanlineStrength: Int = 0,
    index: Int = 0,
    padWith: Byte = ' '.code.toByte(),
): ByteArray {
    val record = ByteArray(LegacySlotParser.RECORD_SIZE) { padWith }
    val bytes = name.toByteArray(Charsets.US_ASCII)
    bytes.copyInto(record, 0, 0, minOf(bytes.size, LegacySlotParser.NAME_SIZE))
    record[25] = presetId.toByte()
    record[26] = if (scanlines) 1 else 0
    record[27] = scanlineStrength.toByte()
    record[28] = index.toByte()
    return record
}

class LegacySlotParserTest {
    @Test
    fun `reads a populated slot`() {
        val slots = LegacySlotParser.parse(slotRecord("Mega Drive", presetId = 5, scanlines = true, scanlineStrength = 0x30))
        assertEquals(1, slots.size)
        val slot = slots.single()
        assertEquals("Mega Drive", slot.name)
        assertEquals('A', slot.slot)
        assertEquals(5, slot.presetId)
        assertTrue(slot.scanlines!!)
        assertEquals(0x30, slot.scanlineStrength)
        assertFalse(slot.empty)
    }

    @Test
    fun `treats the firmware placeholder as empty`() {
        val slot = LegacySlotParser.parse(slotRecord("Empty", presetId = 0)).single()
        assertTrue(slot.empty)
        assertEquals("Slot 1", slot.name)
    }

    @Test
    fun `treats an all-spaces name as empty`() {
        val slot = LegacySlotParser.parse(slotRecord("")).single()
        assertTrue(slot.empty)
    }

    @Test
    fun `stops the name at a non-printable byte`() {
        val record = slotRecord("GoodBad")
        record[4] = 0x01 // a byte the firmware would never write, but a corrupt file might
        assertEquals("Good", LegacySlotParser.parse(record).single().name)
    }

    @Test
    fun `stops the name at a NUL terminator`() {
        val record = slotRecord("Saturn", padWith = 0)
        assertEquals("Saturn", LegacySlotParser.parse(record).single().name)
    }

    @Test
    fun `ignores a trailing partial record`() {
        val payload = slotRecord("One") + slotRecord("Two") + ByteArray(7)
        val slots = LegacySlotParser.parse(payload)
        assertEquals(2, slots.size)
        assertEquals(listOf("One", "Two"), slots.map(PresetSlot::name))
    }

    @Test
    fun `returns nothing for a file shorter than one record`() {
        assertTrue(LegacySlotParser.parse(ByteArray(31)).isEmpty())
    }

    @Test
    fun `never reports more than the firmware slot count`() {
        val payload = ByteArray(LegacySlotParser.RECORD_SIZE * (LegacySlotParser.SLOT_COUNT + 5)) { ' '.code.toByte() }
        assertEquals(LegacySlotParser.SLOT_COUNT, LegacySlotParser.parse(payload).size)
    }

    @Test
    fun `maps every index onto the firmware slot character map`() {
        val payload = ByteArray(LegacySlotParser.RECORD_SIZE * LegacySlotParser.SLOT_COUNT) { ' '.code.toByte() }
        val slots = LegacySlotParser.parse(payload)
        assertEquals(LegacySlotParser.SLOT_CHARS.toList(), slots.map(PresetSlot::slot))
    }
}

class LegacyStateParserTest {
    /** Byte 3-5 use '@' (0x40) as a "byte is present" base, with one option per bit. */
    private fun frame(preset: Char, slot: Char, flags0: Int = 0, flags1: Int = 0, flags2: Int = 0) =
        "#$preset$slot" + (0x40 or flags0).toChar() + (0x40 or flags1).toChar() + (0x40 or flags2).toChar()

    @Test
    fun `rejects a frame that is too short`() {
        assertNull(LegacyStateParser.parse("#3A@@"))
    }

    @Test
    fun `rejects a frame without the marker`() {
        assertNull(LegacyStateParser.parse("!3A@@@"))
    }

    @Test
    fun `reads preset slot and the all-clear flag set`() {
        val state = LegacyStateParser.parse(frame('3', 'A'))!!
        assertEquals("1280x720", state.preset)
        assertEquals('3', state.presetCode)
        assertEquals('A', state.slot)
        assertFalse(state.scanlines!!)
        assertFalse(state.autoGain!!)
        assertEquals(DeinterlaceModes.MOTION_ADAPTIVE, state.deinterlaceMode)
    }

    @Test
    fun `decodes the first flag byte`() {
        val state = LegacyStateParser.parse(frame('1', 'B', flags0 = 0x3f))!!
        assertTrue(state.autoGain!!)
        assertTrue(state.scanlines!!)
        assertTrue(state.lineFilter!!)
        assertTrue(state.peaking!!)
        assertTrue(state.palForce60!!)
        assertTrue(state.outputComponent!!)
    }

    @Test
    fun `decodes the second flag byte`() {
        val state = LegacyStateParser.parse(frame('1', 'B', flags1 = 0x3f))!!
        assertTrue(state.matchedPresets!!)
        assertTrue(state.frameTimeLock!!)
        assertEquals(DeinterlaceModes.BOB, state.deinterlaceMode)
        assertTrue(state.stepResponse!!)
        assertTrue(state.fullHeight!!)
    }

    @Test
    fun `decodes the third flag byte`() {
        val state = LegacyStateParser.parse(frame('1', 'B', flags2 = 0x07))!!
        assertTrue(state.calibrationAdc!!)
        assertTrue(state.preferScalingRgbhv!!)
        assertTrue(state.externalClockDisabled!!)
    }

    @Test
    fun `labels the custom and bypass presets`() {
        assertEquals("Custom", LegacyStateParser.parse(frame('9', 'A'))!!.preset)
        assertEquals("Pass-through", LegacyStateParser.parse(frame('8', 'A'))!!.preset)
        assertEquals(PresetLabels.UNKNOWN, LegacyStateParser.parse(frame('0', 'A'))!!.preset)
    }

    @Test
    fun `reports nothing the frame does not carry`() {
        val state = LegacyStateParser.parse(frame('3', 'A'))!!
        assertNull(state.inputMode)
        assertNull(state.scanlineStrength)
        assertNull(state.brightness)
    }
}
