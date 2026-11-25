package it.uhde.periph.chips.motor

import it.uhde.periph.connection.MockConnection
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DRV2605Test {

    /** Mock whose DEV_RESET and GO bits self-clear like the real chip. */
    private class ChipMock : MockConnection() {
        init { setRegister(0x00, 0x60) }

        override fun write(data: ByteArray) {
            super.write(data)
            if (data.size >= 2 && data[0].toInt() == 0x01) setRegister(0x01, data[1].toInt() and 0x7F)
            if (data.size >= 2 && data[0].toInt() == 0x0C) setRegister(0x0C, 0)
        }
    }

    private fun lastWrite(c: MockConnection, reg: Int): ByteArray? =
        c.writes().lastOrNull { it.size >= 2 && (it[0].toInt() and 0xFF) == reg }?.let { it.copyOfRange(1, it.size) }

    private fun lastByte(c: MockConnection, reg: Int): Int = lastWrite(c, reg)?.get(0)?.toInt()?.and(0xFF) ?: -1

    private fun wrote(c: MockConnection, reg: Int, value: Int): Boolean =
        c.writes().any { it.size == 2 && (it[0].toInt() and 0xFF) == reg && (it[1].toInt() and 0xFF) == value }

    @Test
    fun initSequenceErmAndLra() {
        val erm = ChipMock()
        DRV2605Minimal(erm)
        assertArrayEquals(byteArrayOf(0x01, 0x80.toByte()), erm.writes()[0])
        assertTrue(wrote(erm, 0x01, 0x00))
        assertEquals(0x36, lastByte(erm, 0x1A))
        assertEquals(0x01, lastByte(erm, 0x03))
        val lra = ChipMock()
        DRV2605Minimal(lra, true)
        assertEquals(0xB6, lastByte(lra, 0x1A))
        assertEquals(0x06, lastByte(lra, 0x03))
    }

    @Test
    fun initRejectsWrongDeviceId() {
        val c = ChipMock()
        c.setRegister(0x00, 0xE0)
        assertThrows(IllegalStateException::class.java) { DRV2605Minimal(c) }
    }

    @Test
    fun minimalPlayback() {
        val c = ChipMock()
        val chip = DRV2605Minimal(c)
        chip.playEffect(47)
        assertArrayEquals(byteArrayOf(47, 0), lastWrite(c, 0x04))
        assertEquals(1, lastByte(c, 0x0C))
        assertThrows(IllegalArgumentException::class.java) { chip.playEffect(0) }
        assertThrows(IllegalArgumentException::class.java) { chip.playEffect(124) }
        chip.stop()
        assertEquals(0, lastByte(c, 0x0C))
        c.setRegister(0x0C, 1)
        assertTrue(chip.isPlaying())
        c.setRegister(0x0C, 0)
        assertFalse(chip.isPlaying())
    }

    @Test
    fun sequenceEncodingAndWaitDone() {
        val c = ChipMock()
        val chip = DRV2605Full(c)
        chip.playSequence(DRV2605Full.SequenceStep.effect(1), DRV2605Full.SequenceStep.delay(100),
            DRV2605Full.SequenceStep.effect(10))
        assertArrayEquals(byteArrayOf(1, 0x8A.toByte(), 10, 0), lastWrite(c, 0x04))
        chip.playSequence(*Array(8) { DRV2605Full.SequenceStep.effect(it + 1) })
        assertArrayEquals(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8), lastWrite(c, 0x04))
        assertThrows(IllegalArgumentException::class.java) {
            chip.playSequence(*Array(9) { DRV2605Full.SequenceStep.effect(1) })
        }
        assertThrows(IllegalArgumentException::class.java) { chip.playSequence(DRV2605Full.SequenceStep.delay(2000)) }
        c.setRegister(0x0C, 0)
        assertTrue(chip.waitDone(50))
        c.setRegister(0x0C, 1)
        assertFalse(chip.waitDone(20))
    }

    @Test
    fun modeStandbyLibraryAndHiZ() {
        val c = ChipMock()
        val chip = DRV2605Full(c)
        chip.setMode(5)
        assertEquals(0x05, lastByte(c, 0x01))
        chip.standby()
        assertEquals(0x45, lastByte(c, 0x01))
        chip.wake()
        assertEquals(0x05, lastByte(c, 0x01))
        chip.setHiZ(true)
        assertEquals(0x10, lastByte(c, 0x03) and 0x10)
        chip.setLibrary(6)
        assertEquals(0x16, lastByte(c, 0x03))
        assertThrows(IllegalArgumentException::class.java) { chip.setLibrary(7) }
        c.setRegister(0x1A, 0x36)
        chip.setActuator(true)
        assertEquals(0xB6, lastByte(c, 0x1A))
    }

    @Test
    fun realTimePlayback() {
        val c = ChipMock()
        val chip = DRV2605Full(c)
        chip.rtpLevel(0.5)
        assertTrue(wrote(c, 0x01, 0x05))
        assertEquals(64, lastByte(c, 0x02))
        chip.rtpWrite(200)
        assertEquals(200, lastByte(c, 0x02))
        chip.stop()
        assertEquals(0, lastByte(c, 0x02))
        assertEquals(0x00, lastByte(c, 0x01))
        assertThrows(IllegalArgumentException::class.java) { chip.rtpLevel(1.5) }
        chip.setRtpFormat(true)
        assertEquals(0x08, lastByte(c, 0x1D) and 0x08)
    }

    @Test
    fun loopConstraint() {
        val c = ChipMock()
        val chip = DRV2605Full(c)
        c.setRegister(0x1D, 0x20)
        assertThrows(IllegalStateException::class.java) { chip.setBidirectional(false) }
        chip.setOpenLoop(false)
        assertEquals(0x00, lastByte(c, 0x1D))
        chip.setBidirectional(false)
        assertEquals(0, lastByte(c, 0x1C) and 0x80)
    }

    @Test
    fun voltageConversions() {
        val c = ChipMock()
        val chip = DRV2605Full(c)
        c.setRegister(0x1A, 0x36)
        chip.setRatedVoltage(3.0)
        assertEquals(141, lastByte(c, 0x16))
        chip.setRatedVoltageCode(77)
        assertEquals(77, lastByte(c, 0x16))
        c.setRegister(0x1D, 0x20)
        chip.setOverdriveClamp(3.0)
        assertEquals(137, lastByte(c, 0x17))
        c.setRegister(0x1D, 0x00)
        assertThrows(IllegalStateException::class.java) { chip.setOverdriveClamp(3.0) }
        c.setRegister(0x1A, 0xB6)
        c.setRegister(0x1C, 0x30)
        chip.setRatedVoltage(2.0, 200.0)
        assertEquals(81, lastByte(c, 0x16))
        assertThrows(IllegalArgumentException::class.java) { chip.setRatedVoltage(2.0) }
        chip.setDriveTime(2.5)
        assertEquals(20, lastByte(c, 0x1B) and 0x1F)
        c.setRegister(0x1A, 0x36)
        chip.setDriveTime(5.0)
        assertEquals(20, lastByte(c, 0x1B) and 0x1F)
    }

    @Test
    fun feedbackAndOffsets() {
        val c = ChipMock()
        val chip = DRV2605Full(c)
        c.setRegister(0x1A, 0x80)
        chip.setFeedback(2, 2, 1)
        assertEquals(0x80 or (2 shl 4) or (2 shl 2) or 1, lastByte(c, 0x1A))
        chip.setTimeOffsets(10, -5, 0, -640)
        assertArrayEquals(byteArrayOf(2, 0xFF.toByte(), 0, 0x80.toByte()), lastWrite(c, 0x0D))
        assertThrows(IllegalArgumentException::class.java) { chip.setTimeOffsets(brakeMs = 700) }
    }

    @Test
    fun calibrationFlow() {
        val c = ChipMock()
        val chip = DRV2605Full(c)
        c.setRegister(0x18, 0x0D, 0x6D)
        c.setRegister(0x1A, 0x36)
        val r = chip.calibrate(false, 3.0, 3.0)
        assertTrue(wrote(c, 0x01, 0x07))
        assertEquals(141, lastByte(c, 0x16))
        assertEquals(137, lastByte(c, 0x17))
        assertEquals(0x30, lastByte(c, 0x1E) and 0x30)
        assertTrue(wrote(c, 0x0C, 0x01))
        assertEquals(DRV2605Full.Calibration(true, 0x0D, 0x6D, 2), r)
        assertEquals(0x00, lastByte(c, 0x01))
        c.setRegister(0x00, 0x68)
        assertFalse(chip.calibrate(false, 3.0, 3.0).ok)
        c.setRegister(0x00, 0x60)
        assertThrows(IllegalArgumentException::class.java) { chip.calibrate(true, 2.0, 2.5) }
        chip.calibrate(true, 2.0, 2.5, 200.0)
        assertEquals(0x80, lastByte(c, 0x1A) and 0x80)
        chip.setCalibration(0x20, 0x80, 3)
        assertArrayEquals(byteArrayOf(0x20, 0x80.toByte()), lastWrite(c, 0x18))
        assertEquals(DRV2605Full.Calibration(true, 0x20, 0x80, 3), chip.getCalibration())
    }

    @Test
    fun diagnose() {
        val c = ChipMock()
        val chip = DRV2605Full(c)
        assertTrue(chip.diagnose())
        assertTrue(wrote(c, 0x01, 0x06))
        c.setRegister(0x00, 0x68)
        assertFalse(chip.diagnose())
    }

    @Test
    fun audioToVibe() {
        val c = ChipMock()
        val chip = DRV2605Full(c)
        chip.setAudioToVibe(true, 30, 150, 0.0, 1.8, 0.0, 1.0)
        assertArrayEquals(byteArrayOf(((2 shl 2) or 2).toByte(), 0, 255.toByte(), 0, 255.toByte()), lastWrite(c, 0x11))
        assertEquals(0x04, lastByte(c, 0x01))
        assertEquals(0x20, lastByte(c, 0x1B) and 0x20)
        assertThrows(IllegalArgumentException::class.java) { chip.setAudioToVibe(filterHz = 300) }
    }

    @Test
    fun statusAndReadouts() {
        val c = ChipMock()
        val chip = DRV2605Full(c)
        c.setRegister(0x00, 0x60 or 0x08 or 0x01)
        assertEquals(DRV2605Full.Status(true, false, false, true), chip.readStatus())
        assertEquals(3, chip.readDeviceId())
        c.setRegister(0x21, 255)
        assertEquals(5.6, chip.readSupplyVoltage(), 1e-9)
        c.setRegister(0x22, 0)
        assertNull(chip.readLraFrequency())
        c.setRegister(0x22, 52)
        assertEquals(195.3, chip.readLraFrequency()!!, 0.2)
    }

    @Test
    fun otpGuards() {
        val c = ChipMock()
        val chip = DRV2605Full(c)
        val n = c.writes().size
        assertThrows(IllegalArgumentException::class.java) { chip.programOtp(false) }
        assertEquals(n, c.writes().size)
        c.setRegister(0x1E, 0x04)
        assertTrue(chip.otpProgrammed())
        assertThrows(IllegalStateException::class.java) { chip.programOtp(true) }
    }
}
