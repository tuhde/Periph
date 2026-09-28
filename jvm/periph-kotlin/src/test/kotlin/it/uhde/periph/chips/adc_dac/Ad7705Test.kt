package it.uhde.periph.chips.adc_dac

import it.uhde.periph.connection.MockConnection
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class Ad7705Test {

    @Test
    fun initAndMinimalReads() {
        // mclkHz=4915200 -> CLKDIV=1, CLK=1, FS1:FS0=00 (50 Hz) -> Clock reg = 0x0C
        // (matches the spec's own worked example). Setup reg = MODE_SELF_CAL|GAIN_1|
        // BIPOLAR|UNBUFFERED|FSYNC_RUN = 0x40.
        val connection = MockConnection()
        val sensor = Ad7705Minimal(connection, 2.5f, Ad7705Minimal.MCLK_4_9152MHZ)
        assertArrayEquals(byteArrayOf(0x20, 0x0C), connection.writes()[0])
        assertArrayEquals(byteArrayOf(0x10, 0x40), connection.writes()[1])
        assertArrayEquals(byteArrayOf(0x08), connection.writes()[2])

        assertThrows(IllegalArgumentException::class.java) { Ad7705Minimal(connection, 2.5f, 123) }

        // readRaw / readVoltage: Channel 1, gain 1, bipolar.
        // Data Register CH1 read comm = REG_DATA|RW_READ|CH1 = 0x38.
        // code=0xC000 (49152) -> ((49152-32768)/32768)*(2.5/1) = 1.25 V
        connection.setRegister(0x38, 0xC0, 0x00)
        assertEquals(0xC000, sensor.readRaw())
        assertTrue(Math.abs(sensor.readVoltage() - 1.25f) < 1e-6f)
    }

    @Test
    fun configureChannel2IndependentOfChannel1() {
        // Regression test for a driver bug found while writing this test:
        // configure() only updated the shared gain/bipolar/buffered fields when
        // channel==1, so readVoltage(2) silently converted using channel 1's
        // gain/bipolar instead of channel 2's. A second, separate bug:
        // configureClock() was hardcoded to always write Channel 1's Clock
        // Register, even when configuring channel 2.
        val connection = MockConnection()
        val full = Ad7705Full(connection, 2.5f, Ad7705Minimal.MCLK_4_9152MHZ)

        // configure(2, gain=4, bipolar=false, buffered=true, 250 Hz):
        // Clock reg CH2 (comm=0x21): CLKDIV=1,CLK=1,FS=index(250)=2 -> 0x0E
        // Setup reg CH2 (comm=0x11): MODE_NORMAL|GAIN_4(0x10)|UNIPOLAR(0x04)|BUFFERED(0x02) = 0x16
        full.configure(2, 4, false, true, 250)
        var n = connection.writes().size
        assertArrayEquals(byteArrayOf(0x21, 0x0E), connection.writes()[n - 2])
        assertArrayEquals(byteArrayOf(0x11, 0x16), connection.writes()[n - 1])

        // Data Register CH2 read comm = REG_DATA|RW_READ|CH2 = 0x39.
        // code=0x8000 (32768), gain=4, unipolar -> (32768/65536)*(2.5/4) = 0.3125 V
        connection.setRegister(0x39, 0x80, 0x00)
        assertTrue(Math.abs(full.readVoltage(2) - 0.3125f) < 1e-6f)

        // Channel 1 was never configured, so it must still use the ctor default
        // (gain 1, bipolar) -- unaffected by channel 2's configure() above.
        connection.setRegister(0x38, 0xC0, 0x00)
        assertTrue(Math.abs(full.readVoltage(1) - 1.25f) < 1e-6f)

        assertThrows(IllegalArgumentException::class.java) { full.configure(3, 1, true, false, 50) }
        assertThrows(IllegalArgumentException::class.java) { full.configure(1, 3, true, false, 50) }
    }

    @Test
    fun calibrationUsesConfiguredChannelState() {
        val connection = MockConnection()
        val full = Ad7705Full(connection, 2.5f, Ad7705Minimal.MCLK_4_9152MHZ)
        full.configure(2, 4, false, true, 250)

        // selfCalibrate(2): setup = MODE_SELF_CAL(0x40)|GAIN_4(0x10)|UNIPOLAR(0x04)|BUFFERED(0x02) = 0x56
        // -- channel 2's configured state, not channel 1's defaults.
        full.selfCalibrate(2)
        var n = connection.writes().size
        assertArrayEquals(byteArrayOf(0x11, 0x56), connection.writes()[n - 2])

        // systemCalibrateZero(1) / systemCalibrateFull(1): channel 1's untouched
        // defaults (gain 1, bipolar).
        full.systemCalibrateZero(1)
        n = connection.writes().size
        assertArrayEquals(byteArrayOf(0x10, 0x80.toByte()), connection.writes()[n - 2]) // MODE_ZERO_SYS|GAIN_1|BIPOLAR

        full.systemCalibrateFull(1)
        n = connection.writes().size
        assertArrayEquals(byteArrayOf(0x10, 0xC0.toByte()), connection.writes()[n - 2]) // MODE_FULL_SYS|GAIN_1|BIPOLAR
    }

    @Test
    fun calibrationRegistersAndPowerControl() {
        val connection = MockConnection()
        val full = Ad7705Full(connection, 2.5f, Ad7705Minimal.MCLK_4_9152MHZ)

        // Zero-Scale reg CH1 read comm = REG_OFFSET|RW_READ|CH1 = 0x68.
        connection.setRegister(0x68, 0x12, 0x34, 0x56)
        assertEquals(0x123456, full.getOffsetCalibration(1))

        full.setOffsetCalibration(0xABCDEF, 1)
        assertArrayEquals(
            byteArrayOf(0x60, 0xAB.toByte(), 0xCD.toByte(), 0xEF.toByte()),
            connection.writes().last(),
        )

        // Full-Scale reg CH1 read comm = REG_GAIN|RW_READ|CH1 = 0x78.
        connection.setRegister(0x78, 0x01, 0x02, 0x03)
        assertEquals(0x010203, full.getGainCalibration(1))

        full.setGainCalibration(0x040506, 1)
        assertArrayEquals(byteArrayOf(0x70, 0x04, 0x05, 0x06), connection.writes().last())

        // standby(): comm(COMM,WRITE,CH1)|STBY_SLEEP = 0x04.
        full.standby()
        assertArrayEquals(byteArrayOf(0x04), connection.writes().last())

        // wakeup(): comm(COMM,WRITE,CH1)|STBY_RUN = 0x00, then wait_drdy.
        full.wakeup()
        val n = connection.writes().size
        assertArrayEquals(byteArrayOf(0x00), connection.writes()[n - 2])
        assertArrayEquals(byteArrayOf(0x08), connection.writes()[n - 1])
    }

    @Test
    fun reset() {
        val connection = MockConnection()
        val full = Ad7705Full(connection, 2.5f, Ad7705Minimal.MCLK_4_9152MHZ)
        assertThrows(IllegalStateException::class.java) { full.reset() }

        // NOTE: unlike Python/C++/Node.js/Go, Ad7705Minimal's constructor here
        // does not accept a resetPin at all, so RESET is never auto-pulsed
        // "before configuration" the way the spec describes -- only an
        // explicit reset() call (below) pulses it. Left as-is -- a bigger,
        // cross-language structural fix than this test suite targets.
        val calls = mutableListOf<Boolean>()
        val pin = Ad7705ResetPin { high -> calls.add(high) }
        val connection2 = MockConnection()
        val withReset = Ad7705Full(connection2, 2.5f, Ad7705Minimal.MCLK_4_9152MHZ, pin)

        withReset.reset()
        assertEquals(listOf(false, true), calls)
    }
}
