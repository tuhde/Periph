package it.uhde.periph.chips.accelerometer

import it.uhde.periph.connection.MockConnection
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.IOException

class Lis3dhTest {

    private fun newConnection(): MockConnection {
        val c = MockConnection()
        c.setRegister(Lis3dhMinimal.REG_WHO_AM_I, 0x33)
        // Sub-address for the I²C multi-byte auto-increment is reg | 0x80:
        // X = +1.000 g, Y = 0, Z = -0.500 g at 12-bit / ±2 g (1 mg/LSB).
        c.setRegister(
            Lis3dhMinimal.REG_OUT_X_L or 0x80,
            0x80, 0x3E,
            0x00, 0x00,
            0xC0, 0xE0
        )
        return c
    }

    private fun lastWriteTo(connection: MockConnection, reg: Int): Int {
        val writes = connection.writes()
        for (i in writes.indices.reversed()) {
            val w = writes[i]
            if (w.size == 2 && (w[0].toInt() and 0xFF) == reg) return w[1].toInt() and 0xFF
        }
        return -1
    }

    private fun readSubAddressUsed(connection: MockConnection, sub: Int): Boolean {
        for (w in connection.writes()) {
            if (w.size == 1 && (w[0].toInt() and 0xFF) == sub) return true
        }
        return false
    }

    private fun writeWithBitSeen(connection: MockConnection, reg: Int, bit: Int): Boolean {
        for (w in connection.writes()) {
            if (w.size == 2 && (w[0].toInt() and 0xFF) == reg && (w[1].toInt() and bit) != 0) return true
        }
        return false
    }

    // -------------------------------------------------------------------------
    // Initialization sequence
    // -------------------------------------------------------------------------

    @Test
    fun initSequenceWritesTheControlRegisters() {
        val connection = newConnection()
        Lis3dhMinimal(connection)

        assertEquals(0x00, lastWriteTo(connection, Lis3dhMinimal.REG_CTRL_REG2))
        assertEquals(0x00, lastWriteTo(connection, Lis3dhMinimal.REG_CTRL_REG3))
        assertEquals(0x00, lastWriteTo(connection, Lis3dhMinimal.REG_CTRL_REG5))
        assertEquals(0x00, lastWriteTo(connection, Lis3dhMinimal.REG_CTRL_REG6))
        assertEquals(0x00, lastWriteTo(connection, Lis3dhMinimal.REG_FIFO_CTRL))
        assertEquals(0x00, lastWriteTo(connection, Lis3dhMinimal.REG_TEMP_CFG))
        assertEquals(0x88, lastWriteTo(connection, Lis3dhMinimal.REG_CTRL_REG4))
        assertEquals(0x57, lastWriteTo(connection, Lis3dhMinimal.REG_CTRL_REG1))
    }

    @Test
    fun initSequenceOrderIsDatasheetOrder() {
        val connection = newConnection()
        Lis3dhMinimal(connection)

        val expected = intArrayOf(
            Lis3dhMinimal.REG_CTRL_REG2,
            Lis3dhMinimal.REG_CTRL_REG3,
            Lis3dhMinimal.REG_CTRL_REG5,
            Lis3dhMinimal.REG_CTRL_REG6,
            Lis3dhMinimal.REG_FIFO_CTRL,
            Lis3dhMinimal.REG_TEMP_CFG,
            Lis3dhMinimal.REG_CTRL_REG4,
            Lis3dhMinimal.REG_CTRL_REG1
        )
        var index = 0
        for (w in connection.writes()) {
            if (w.size != 2) continue
            val reg = w[0].toInt() and 0xFF
            if (index < expected.size && reg == expected[index]) index++
        }
        assertEquals(expected.size, index)
    }

    @Test
    fun initDoesNotTouchTheReservedRegisters() {
        val connection = newConnection()
        Lis3dhMinimal(connection)
        for (w in connection.writes()) {
            if (w.size != 2) continue
            val reg = w[0].toInt() and 0xFF
            assertFalse(reg <= 0x06, "reserved register written")
            assertFalse(reg == 0x0E, "reserved register written")
            assertFalse(reg >= 0x10 && reg <= 0x1D, "reserved register written")
            assertFalse(reg == Lis3dhMinimal.REG_CTRL_REG0, "CTRL_REG0 must stay untouched")
        }
    }

    @Test
    fun badWhoAmIThrows() {
        val connection = MockConnection()
        connection.setRegister(Lis3dhMinimal.REG_WHO_AM_I, 0x44)
        assertThrows(IOException::class.java) { Lis3dhMinimal(connection) }
    }

    // -------------------------------------------------------------------------
    // Reading
    // -------------------------------------------------------------------------

    @Test
    fun readUsesTheAutoIncrementSubAddress() {
        val connection = newConnection()
        val chip = Lis3dhMinimal(connection)
        chip.read()
        assertTrue(readSubAddressUsed(connection, Lis3dhMinimal.REG_OUT_X_L or 0x80))
    }

    @Test
    fun readScalesToGrams() {
        val connection = newConnection()
        val chip = Lis3dhMinimal(connection)
        val xyz = chip.read()
        assertEquals(1.0, xyz[0], 1e-9)
        assertEquals(0.0, xyz[1], 1e-9)
        assertEquals(-0.5, xyz[2], 1e-9)
    }

    @Test
    fun readRawReturnsSignedCounts() {
        val connection = newConnection()
        val chip = Lis3dhFull(connection)
        val raw = chip.readRaw()
        assertEquals(1000, raw[0])
        assertEquals(0, raw[1])
        assertEquals(-500, raw[2])
    }

    @Test
    fun statusAndNewData() {
        val connection = newConnection()
        val chip = Lis3dhFull(connection)
        connection.setRegister(Lis3dhMinimal.REG_STATUS, 0x0F)
        assertTrue(chip.newDataAvailable())
        assertEquals(0x0F, chip.readStatus())
    }

    // -------------------------------------------------------------------------
    // Configuration
    // -------------------------------------------------------------------------

    @Test
    fun setRangeUpdatesTheFsField() {
        val connection = newConnection()
        val chip = Lis3dhFull(connection)
        chip.setRange(8)
        // (0x88 and not 0x30) or (2 shl 4) = 0xA8
        assertEquals(0xA8, lastWriteTo(connection, Lis3dhMinimal.REG_CTRL_REG4))
    }

    @Test
    fun setRangeRejectsUnknownRanges() {
        val connection = newConnection()
        val chip = Lis3dhFull(connection)
        assertThrows(IllegalArgumentException::class.java) { chip.setRange(3) }
    }

    @Test
    fun setDataRateUpdatesTheOdrField() {
        val connection = newConnection()
        val chip = Lis3dhFull(connection)
        chip.setDataRate(50)
        // (0x57 and 0x0F) or 0x40 = 0x47
        assertEquals(0x47, lastWriteTo(connection, Lis3dhMinimal.REG_CTRL_REG1))
    }

    @Test
    fun setDataRateValidatesTheModeCombination() {
        val connection = newConnection()
        val chip = Lis3dhFull(connection)
        // 1620 Hz and 5376 Hz are low-power mode only.
        assertThrows(IllegalArgumentException::class.java) { chip.setDataRate(1620) }
        assertThrows(IllegalArgumentException::class.java) { chip.setDataRate(5376) }
        assertThrows(IllegalArgumentException::class.java) { chip.setDataRate(999) }
    }

    @Test
    fun setModeUpdatesLpenAndHr() {
        val connection = newConnection()
        val chip = Lis3dhFull(connection)

        chip.setMode(Lis3dhMinimal.MODE_LOW_POWER)
        assertEquals(0x5F, lastWriteTo(connection, Lis3dhMinimal.REG_CTRL_REG1))
        assertEquals(0x80, lastWriteTo(connection, Lis3dhMinimal.REG_CTRL_REG4))

        chip.setMode(Lis3dhMinimal.MODE_NORMAL)
        assertEquals(0x57, lastWriteTo(connection, Lis3dhMinimal.REG_CTRL_REG1))
        assertEquals(0x80, lastWriteTo(connection, Lis3dhMinimal.REG_CTRL_REG4))

        chip.setMode(Lis3dhMinimal.MODE_HIGH_RESOLUTION)
        assertEquals(0x57, lastWriteTo(connection, Lis3dhMinimal.REG_CTRL_REG1))
        assertEquals(0x88, lastWriteTo(connection, Lis3dhMinimal.REG_CTRL_REG4))
    }

    @Test
    fun setModeRejectsUnknownModes() {
        val connection = newConnection()
        val chip = Lis3dhFull(connection)
        assertThrows(IllegalArgumentException::class.java) { chip.setMode(7) }
    }

    @Test
    fun lowPowerModeShiftsEightBits() {
        val connection = newConnection()
        val chip = Lis3dhFull(connection)
        chip.setMode(Lis3dhMinimal.MODE_LOW_POWER)
        // 8-bit: 0x1000 shr 8 = 16 counts; ±2 g low power is 16 mg/LSB.
        connection.setRegister(
            Lis3dhMinimal.REG_OUT_X_L or 0x80,
            0x00, 0x10,
            0x00, 0x00,
            0x00, 0x00
        )
        val xyz = chip.read()
        assertEquals(16 * 16 / 1000.0, xyz[0], 1e-9)
    }

    @Test
    fun setAxesUpdatesTheAxisBits() {
        val connection = newConnection()
        val chip = Lis3dhFull(connection)
        chip.setAxes(true, false, true)
        // (0x57 and 0xF8) or 0x05 = 0x55
        assertEquals(0x55, lastWriteTo(connection, Lis3dhMinimal.REG_CTRL_REG1))
    }

    @Test
    fun powerDownClearsTheOdrField() {
        val connection = newConnection()
        val chip = Lis3dhFull(connection)
        chip.powerDown()
        assertEquals(0x07, lastWriteTo(connection, Lis3dhMinimal.REG_CTRL_REG1))
    }

    // -------------------------------------------------------------------------
    // Temperature and auxiliary ADC
    // -------------------------------------------------------------------------

    @Test
    fun temperatureEnablesTheAdcAndReturnsARelativeDelta() {
        val connection = newConnection()
        val chip = Lis3dhFull(connection)
        connection.setRegister(Lis3dhMinimal.REG_OUT_ADC3_H, 0xF0)
        assertEquals(-16, chip.readTemperatureDelta())
        // ADC_EN + TEMP_EN
        assertEquals(0xC0, lastWriteTo(connection, Lis3dhMinimal.REG_TEMP_CFG))
    }

    @Test
    fun adcReadsTheAuxiliaryChannels() {
        val connection = newConnection()
        val chip = Lis3dhFull(connection)
        // Channel 1: raw16 = 0x0400 = 1024 -> 16 counts at 10-bit.
        connection.setRegister(0x08 or 0x80, 0x00, 0x04)
        assertEquals(1.200 + 16 * (0.400 / 512.0), chip.readAdc(1), 1e-9)
    }

    @Test
    fun adcChannel3IsRejectedWhileTheTemperatureSensorIsOn() {
        val connection = newConnection()
        val chip = Lis3dhFull(connection)
        connection.setRegister(Lis3dhMinimal.REG_TEMP_CFG, 0x40)
        assertThrows(IllegalArgumentException::class.java) { chip.readAdc(3) }
        assertThrows(IllegalArgumentException::class.java) { chip.readAdc(4) }
    }

    @Test
    fun enableAdcTogglesTheAdcEnableBit() {
        val connection = newConnection()
        val chip = Lis3dhFull(connection)
        chip.enableAdc(true)
        assertEquals(0x80, lastWriteTo(connection, Lis3dhMinimal.REG_TEMP_CFG))
        chip.enableAdc(false)
        assertEquals(0x00, lastWriteTo(connection, Lis3dhMinimal.REG_TEMP_CFG))
    }

    // -------------------------------------------------------------------------
    // High-pass filter
    // -------------------------------------------------------------------------

    @Test
    fun highPassConfiguration() {
        val connection = newConnection()
        val chip = Lis3dhFull(connection)
        chip.setHighPass(3, 2, true, true, true, true)
        // (3 shl 6) or (2 shl 4) or FDS or HPCLICK or HP_IA2 or HP_IA1 = 0xEF
        assertEquals(0xEF, lastWriteTo(connection, Lis3dhMinimal.REG_CTRL_REG2))
    }

    @Test
    fun highPassRejectsInvalidCodes() {
        val connection = newConnection()
        val chip = Lis3dhFull(connection)
        assertThrows(IllegalArgumentException::class.java) { chip.setHighPass(4, 0, false, false, false, false) }
        assertThrows(IllegalArgumentException::class.java) { chip.setHighPass(0, 4, false, false, false, false) }
    }

    @Test
    fun referenceRegister() {
        val connection = newConnection()
        val chip = Lis3dhFull(connection)
        connection.setRegister(Lis3dhMinimal.REG_REFERENCE, 0x42)
        assertEquals(0x42, chip.resetHighPass())
        chip.setReference(0x7F)
        assertEquals(0x7F, lastWriteTo(connection, Lis3dhMinimal.REG_REFERENCE))
        assertThrows(IllegalArgumentException::class.java) { chip.setReference(256) }
    }

    // -------------------------------------------------------------------------
    // FIFO
    // -------------------------------------------------------------------------

    @Test
    fun fifoConfiguration() {
        val connection = newConnection()
        val chip = Lis3dhFull(connection)
        chip.setFifo(2, 16, false)
        // (2 shl 6) or 16 = 0x90
        assertEquals(0x90, lastWriteTo(connection, Lis3dhMinimal.REG_FIFO_CTRL))
        assertEquals(0x40, lastWriteTo(connection, Lis3dhMinimal.REG_CTRL_REG5))

        chip.setFifo(0, 0, true)
        assertEquals(0x20, lastWriteTo(connection, Lis3dhMinimal.REG_FIFO_CTRL))
        assertEquals(0x00, lastWriteTo(connection, Lis3dhMinimal.REG_CTRL_REG5))
    }

    @Test
    fun fifoLevelAndStatus() {
        val connection = newConnection()
        val chip = Lis3dhFull(connection)
        connection.setRegister(Lis3dhMinimal.REG_FIFO_SRC, 0xE3)
        assertEquals(3, chip.fifoLevel())
        val status = chip.readFifoStatus()
        assertTrue(status.watermark)
        assertTrue(status.overrun)
        assertTrue(status.empty)
        assertEquals(3, status.level)
    }

    @Test
    fun readFifoDrainsTheSampleSets() {
        val connection = newConnection()
        val chip = Lis3dhFull(connection)
        connection.setRegister(Lis3dhMinimal.REG_FIFO_SRC, 0x03)
        val samples = chip.readFifo()
        assertEquals(3, samples.size)
        assertEquals(1.0, samples[0][0], 1e-9)
        assertEquals(-0.5, samples[2][2], 1e-9)
    }

    @Test
    fun readFifoNeverReadsMoreThanTheFifoHolds() {
        val connection = newConnection()
        val chip = Lis3dhFull(connection)
        connection.setRegister(Lis3dhMinimal.REG_FIFO_SRC, 0x02)
        assertEquals(2, chip.readFifo(10).size)
    }

    // -------------------------------------------------------------------------
    // Inertial interrupt generators
    // -------------------------------------------------------------------------

    @Test
    fun motionInterruptConfiguration() {
        val connection = newConnection()
        val chip = Lis3dhFull(connection)
        chip.setMotionInterrupt(1, 0x35, 0.5, 10.0, true)
        // AOI + 0x35
        assertEquals(0xB5, lastWriteTo(connection, Lis3dhMinimal.REG_INT1_CFG))
        // round(0.5 g * 1000 / 16 mg) = 31
        assertEquals(31, lastWriteTo(connection, Lis3dhMinimal.REG_INT1_THS))
        // round(10 ms * 100 Hz / 1000) = 1
        assertEquals(1, lastWriteTo(connection, Lis3dhMinimal.REG_INT1_DUR))
    }

    @Test
    fun motionInterruptUsesGenerator2Registers() {
        val connection = newConnection()
        val chip = Lis3dhFull(connection)
        chip.setMotionInterrupt(2, 0x01, 0.16, 0.0, false)
        assertEquals(0x01, lastWriteTo(connection, Lis3dhMinimal.REG_INT2_CFG))
        // round(0.16 g * 1000 / 16 mg) = 10
        assertEquals(10, lastWriteTo(connection, Lis3dhMinimal.REG_INT2_THS))
        assertThrows(IllegalArgumentException::class.java) { chip.setMotionInterrupt(3, 0x01, 0.16, 0.0, false) }
    }

    @Test
    fun freeFallConfiguration() {
        val connection = newConnection()
        val chip = Lis3dhFull(connection)
        chip.setFreeFall(1, 0.35, 60.0)
        assertEquals(0x95, lastWriteTo(connection, Lis3dhMinimal.REG_INT1_CFG))
        // round(0.35 g * 1000 / 16 mg) = 22
        assertEquals(22, lastWriteTo(connection, Lis3dhMinimal.REG_INT1_THS))
        // round(60 ms * 100 Hz / 1000) = 6
        assertEquals(6, lastWriteTo(connection, Lis3dhMinimal.REG_INT1_DUR))
    }

    @Test
    fun orientationConfiguration() {
        val connection = newConnection()
        val chip = Lis3dhFull(connection)
        chip.setOrientation(1, false, false)
        // 6D movement recognition with all six axis zones.
        assertEquals(0x7F, lastWriteTo(connection, Lis3dhMinimal.REG_INT1_CFG))
        assertEquals(0x00, lastWriteTo(connection, Lis3dhMinimal.REG_CTRL_REG5))

        chip.setOrientation(2, true, true)
        // 6D position recognition with the four 4D zones.
        assertEquals(0xCF, lastWriteTo(connection, Lis3dhMinimal.REG_INT2_CFG))
        // D4D_INT2
        assertEquals(0x01, lastWriteTo(connection, Lis3dhMinimal.REG_CTRL_REG5))
    }

    @Test
    fun latchConfiguration() {
        val connection = newConnection()
        val chip = Lis3dhFull(connection)
        chip.setLatch(1, true)
        assertEquals(0x08, lastWriteTo(connection, Lis3dhMinimal.REG_CTRL_REG5))
        chip.setLatch(2, true)
        assertEquals(0x0A, lastWriteTo(connection, Lis3dhMinimal.REG_CTRL_REG5))
    }

    @Test
    fun readInterruptSourceReturnsTheSourceByte() {
        val connection = newConnection()
        val chip = Lis3dhFull(connection)
        connection.setRegister(Lis3dhMinimal.REG_INT1_SRC, 0x60)
        connection.setRegister(Lis3dhMinimal.REG_INT2_SRC, 0x40)
        assertEquals(0x60, chip.readInterruptSource(1))
        assertEquals(0x40, chip.readInterruptSource(2))
        assertThrows(IllegalArgumentException::class.java) { chip.readInterruptSource(3) }
    }

    // -------------------------------------------------------------------------
    // Click detection
    // -------------------------------------------------------------------------

    @Test
    fun clickConfiguration() {
        val connection = newConnection()
        val chip = Lis3dhFull(connection)
        chip.setClick(0, 0x04, 0.8, 20.0, 40.0, 200.0, true)
        // Double click on Z: ZD is bit 5.
        assertEquals(0x20, lastWriteTo(connection, Lis3dhMinimal.REG_CLICK_CFG))
        // round(0.8 g * 1000 / 16 mg) = 50, latched -> 0x80
        assertEquals(0xB2, lastWriteTo(connection, Lis3dhMinimal.REG_CLICK_THS))
        assertEquals(2, lastWriteTo(connection, Lis3dhMinimal.REG_TIME_LIMIT))
        assertEquals(4, lastWriteTo(connection, Lis3dhMinimal.REG_TIME_LATENCY))
        assertEquals(20, lastWriteTo(connection, Lis3dhMinimal.REG_TIME_WINDOW))
    }

    @Test
    fun clickSourceIsReadBack() {
        val connection = newConnection()
        val chip = Lis3dhFull(connection)
        connection.setRegister(Lis3dhMinimal.REG_CLICK_SRC, 0x48)
        assertEquals(0x48, chip.readClickSource())
    }

    // -------------------------------------------------------------------------
    // Activity function
    // -------------------------------------------------------------------------

    @Test
    fun activityConfiguration() {
        val connection = newConnection()
        val chip = Lis3dhFull(connection)
        chip.setActivity(0.5, 100.0)
        // round(0.5 g * 1000 / 16 mg) = 31
        assertEquals(31, lastWriteTo(connection, Lis3dhMinimal.REG_ACT_THS))
        // round((100 ms * 100 Hz / 1000 - 1) / 8) = 1
        assertEquals(1, lastWriteTo(connection, Lis3dhMinimal.REG_ACT_DUR))
    }

    @Test
    fun activityRejectsAZeroThreshold() {
        val connection = newConnection()
        val chip = Lis3dhFull(connection)
        assertThrows(IllegalArgumentException::class.java) { chip.setActivity(0.001, 100.0) }
    }

    // -------------------------------------------------------------------------
    // Interrupt routing and polling
    // -------------------------------------------------------------------------

    @Test
    fun interruptPolarity() {
        val connection = newConnection()
        val chip = Lis3dhFull(connection)
        chip.setInterruptPolarity(true)
        assertEquals(0x02, lastWriteTo(connection, Lis3dhMinimal.REG_CTRL_REG6))
        chip.setInterruptPolarity(false)
        assertEquals(0x00, lastWriteTo(connection, Lis3dhMinimal.REG_CTRL_REG6))
    }

    @Test
    fun enableInterruptRoutesToTheRightRegister() {
        val connection = newConnection()
        val chip = Lis3dhFull(connection)

        chip.enableInterrupt(Lis3dhSource.SOURCE_CLICK, 1)
        assertEquals(0x80, lastWriteTo(connection, Lis3dhMinimal.REG_CTRL_REG3))

        chip.enableInterrupt(Lis3dhSource.SOURCE_CLICK, 2)
        assertEquals(0x80, lastWriteTo(connection, Lis3dhMinimal.REG_CTRL_REG6))

        // INT1-only sources ignore the pin.
        chip.enableInterrupt(Lis3dhSource.SOURCE_DATA_READY, 2)
        assertEquals(0x90, lastWriteTo(connection, Lis3dhMinimal.REG_CTRL_REG3))

        // INT2-only sources ignore the pin.
        chip.enableInterrupt(Lis3dhSource.SOURCE_ACTIVITY, 1)
        assertEquals(0x88, lastWriteTo(connection, Lis3dhMinimal.REG_CTRL_REG6))
    }

    @Test
    fun disableInterruptClearsTheRoutingBit() {
        val connection = newConnection()
        val chip = Lis3dhFull(connection)
        chip.enableInterrupt(Lis3dhSource.SOURCE_CLICK, 1)
        chip.disableInterrupt(Lis3dhSource.SOURCE_CLICK, 1)
        assertEquals(0x00, lastWriteTo(connection, Lis3dhMinimal.REG_CTRL_REG3))
    }

    @Test
    fun enableInterruptRejectsUnknownSourcesAndPins() {
        val connection = newConnection()
        val chip = Lis3dhFull(connection)
        assertThrows(IllegalArgumentException::class.java) { chip.enableInterrupt(0x8000, 1) }
        assertThrows(IllegalArgumentException::class.java) { chip.enableInterrupt(Lis3dhSource.SOURCE_CLICK, 3) }
    }

    @Test
    fun pollInterruptCombinesTheEnabledSources() {
        val connection = newConnection()
        val chip = Lis3dhFull(connection)

        // Nothing enabled -> nothing reported.
        connection.setRegister(Lis3dhMinimal.REG_STATUS, 0x08)
        connection.setRegister(Lis3dhMinimal.REG_INT1_SRC, 0x40)
        connection.setRegister(Lis3dhMinimal.REG_CLICK_SRC, 0x40)
        assertEquals(0, chip.pollInterrupt())

        chip.enableInterrupt(Lis3dhSource.SOURCE_DATA_READY, 1)
        chip.enableInterrupt(Lis3dhSource.SOURCE_IA1, 1)
        chip.enableInterrupt(Lis3dhSource.SOURCE_CLICK, 1)
        val status = chip.pollInterrupt()
        assertEquals(
            Lis3dhSource.SOURCE_DATA_READY or
                Lis3dhSource.SOURCE_IA1 or
                Lis3dhSource.SOURCE_CLICK,
            status
        )
    }

    @Test
    fun pollInterruptReportsTheFifoFlags() {
        val connection = newConnection()
        val chip = Lis3dhFull(connection)
        chip.enableInterrupt(Lis3dhSource.SOURCE_WATERMARK, 1)
        chip.enableInterrupt(Lis3dhSource.SOURCE_OVERRUN, 1)
        connection.setRegister(Lis3dhMinimal.REG_FIFO_SRC, 0xC0)
        assertEquals(
            Lis3dhSource.SOURCE_WATERMARK or Lis3dhSource.SOURCE_OVERRUN,
            chip.pollInterrupt()
        )
    }

    // -------------------------------------------------------------------------
    // System functions
    // -------------------------------------------------------------------------

    @Test
    fun sdoPullUp() {
        val connection = newConnection()
        val chip = Lis3dhFull(connection)
        chip.setSdoPullup(false)
        assertEquals(0x90, lastWriteTo(connection, Lis3dhMinimal.REG_CTRL_REG0))
        chip.setSdoPullup(true)
        assertEquals(0x10, lastWriteTo(connection, Lis3dhMinimal.REG_CTRL_REG0))
    }

    @Test
    fun rebootSetsBootAndRerunsTheInitSequence() {
        val connection = newConnection()
        val chip = Lis3dhFull(connection)
        chip.reboot()
        assertTrue(writeWithBitSeen(connection, Lis3dhMinimal.REG_CTRL_REG5, 0x80))
        assertEquals(0x57, lastWriteTo(connection, Lis3dhMinimal.REG_CTRL_REG1))
        assertEquals(0x88, lastWriteTo(connection, Lis3dhMinimal.REG_CTRL_REG4))
    }

    @Test
    fun selfTestDetectsTheExpectedDelta() {
        val connection = SelfTestConnection()
        connection.setRegister(Lis3dhMinimal.REG_WHO_AM_I, 0x33)
        val chip = Lis3dhFull(connection)
        assertTrue(chip.selfTest(1))
    }

    @Test
    fun selfTestFailsWithoutADelta() {
        val connection = newConnection()
        val chip = Lis3dhFull(connection)
        assertFalse(chip.selfTest(1))
        assertThrows(IllegalArgumentException::class.java) { chip.selfTest(3) }
    }

    @Test
    fun selfTestRestoresTheConfiguration() {
        val connection = SelfTestConnection()
        connection.setRegister(Lis3dhMinimal.REG_WHO_AM_I, 0x33)
        val chip = Lis3dhFull(connection)
        chip.selfTest(1)
        assertEquals(0x57, lastWriteTo(connection, Lis3dhMinimal.REG_CTRL_REG1))
        assertEquals(0x88, lastWriteTo(connection, Lis3dhMinimal.REG_CTRL_REG4))
    }

    /**
     * Mock that reports a self-test step: the acceleration jumps by 100
     * counts as soon as `CTRL_REG4.ST` is set, so the self-test delta lands
     * inside the datasheet's 17..360 LSb window.
     */
    private class SelfTestConnection : MockConnection() {
        override fun writeRead(data: ByteArray, n: Int): ByteArray {
            val reg = data[0].toInt() and 0xFF
            if (n == 6 && (reg and 0x7F) == Lis3dhMinimal.REG_OUT_X_L) {
                val ctrl4 = registers()[Lis3dhMinimal.REG_CTRL_REG4] ?: 0
                val out = ByteArray(6)
                if (ctrl4 and 0x06 != 0) {
                    // 100 counts at 10-bit: raw16 = 100 shl 6 = 0x1900.
                    out[1] = 0x19.toByte()
                    out[3] = 0x19.toByte()
                    out[5] = 0x19.toByte()
                }
                writes().add(data.clone())
                return out
            }
            return super.writeRead(data, n)
        }
    }
}
