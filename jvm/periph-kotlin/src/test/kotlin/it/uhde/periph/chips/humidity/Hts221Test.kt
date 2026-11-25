package it.uhde.periph.chips.humidity

import it.uhde.periph.connection.EdgeHandler
import it.uhde.periph.connection.EdgeTrigger
import it.uhde.periph.connection.InputPin
import it.uhde.periph.connection.MockConnection
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

class Hts221Test {

    /** Mock that clears CTRL_REG2.BOOT like the real chip and can expose an INT pin. */
    class ChipMock : MockConnection() {
        var stuckBoot = false
        var pin: InputPin? = null

        override fun write(data: ByteArray) {
            super.write(data)
            if (!stuckBoot && data.size == 2 && (data[0].toInt() and 0xFF) == 0x21) {
                registers()[0x21] = (data[1].toInt() and 0xFF) and 0x80.inv()
            }
        }

        override fun intPin(): InputPin? = pin
    }

    class FakePin : InputPin {
        var handler: EdgeHandler? = null
        var trigger: EdgeTrigger? = null
        override fun onEdge(handler: EdgeHandler, trigger: EdgeTrigger) {
            this.handler = handler
            this.trigger = trigger
        }
        override fun offEdge(handler: EdgeHandler) {
            if (this.handler === handler) this.handler = null
        }
        override fun isHigh(): Boolean = false
        override fun close() {}
    }

    private lateinit var mock: ChipMock

    private fun make(): ChipMock {
        val m = ChipMock()
        // Calibration block at 0xB0 (sub-address with auto-increment):
        // H0_rH_x2=40, H1_rH_x2=80, T0_x8=80, T1_x8=160, msb=0,
        // H0_T0_OUT=0x4000, H1_T0_OUT=0x6000, T0_OUT=300, T1_OUT=500
        m.setRegister(0xB0, 40, 80, 80, 160, 0, 0, 0x00, 0x40, 0, 0, 0x00, 0x60, 0x2C, 0x01, 0xF4, 0x01)
        m.setRegister(0x0F, 0xBC)
        m.setRegister(0x27, 0x03)
        // Burst 0xA8: H_OUT = 0x5000 (30 %rH), T_OUT = 400 (15.0 degC)
        m.setRegister(0xA8, 0x00, 0x50, 0x90, 0x01)
        mock = m
        return m
    }

    private fun wrote(reg: Int, value: Int): Boolean =
        mock.writes().any { it.size == 2 && (it[0].toInt() and 0xFF) == reg && (it[1].toInt() and 0xFF) == value }

    @Test
    fun constructorReadsCalibrationAndStartsOneShot() {
        make()
        Hts221Minimal(mock)
        assertTrue(wrote(0x20, 0x84))
        assertEquals(0xB0, mock.writes()[0][0].toInt() and 0xFF)
    }

    @Test
    fun readConvertsWithCalibration() {
        make()
        val v = Hts221Minimal(mock).read()
        assertEquals(15.0, v[0], 1e-3)
        assertEquals(30.0, v[1], 1e-3)
        assertTrue(wrote(0x21, 0x01))
    }

    @Test
    fun humidityIsClamped() {
        make()
        mock.setRegister(0xB6, 0x00, 0x00, 0, 0, 0x00, 0x10) // H0_T0_OUT=0, H1_T0_OUT=0x1000
        var s = Hts221Minimal(mock)
        mock.setRegister(0xA8, 0xFF, 0x7F, 0, 0)
        assertEquals(100.0, s.read()[1], 1e-6)
        make()
        s = Hts221Minimal(mock)
        mock.setRegister(0xA8, 0x00, 0x80, 0, 0)
        assertEquals(0.0, s.read()[1], 1e-6)
    }

    @Test
    fun readTimesOutWhenNeverReady() {
        make()
        mock.setRegister(0x27, 0x00)
        val s = Hts221Minimal(mock)
        assertThrows(IOException::class.java) { s.read() }
    }

    @Test
    fun calibrationTemperatureMsbBits() {
        make()
        mock.setRegister(0xB5, 0x0D) // T0 bits 9:8 = 01, T1 bits 9:8 = 11
        val cal = Hts221Full(mock).readCalibration()
        assertEquals((256 + 80) / 8.0, cal[2], 1e-9)
        assertEquals((768 + 160) / 8.0, cal[3], 1e-9)
    }

    @Test
    fun singleValuesRawAndContinuous() {
        make()
        val f = Hts221Full(mock)
        assertEquals(30.0, f.readHumidity(), 1e-3)
        assertEquals(15.0, f.readTemperature(), 1e-3)
        assertEquals(0x5000, f.readRawHumidity())
        assertEquals(400, f.readRawTemperature())
        mock.setRegister(0xA8, 0x00, 0xF0, 0, 0)
        assertEquals(-4096, f.readRawHumidity())
        mock.setRegister(0xA8, 0x00, 0x50, 0x90, 0x01)
        val c = f.readContinuous()
        assertEquals(15.0, c[0], 1e-3)
        assertEquals(30.0, c[1], 1e-3)
    }

    @Test
    fun calibrationValues() {
        make()
        val cal = Hts221Full(mock).readCalibration()
        assertArrayEquals(doubleArrayOf(20.0, 40.0, 10.0, 20.0, 0x4000.toDouble(), 0x6000.toDouble(), 300.0, 500.0), cal, 1e-9)
    }

    @Test
    fun outputDataRate() {
        make()
        val f = Hts221Full(mock)
        val hz = doubleArrayOf(12.5, 7.0, 1.0, 0.0)
        val ctrl = intArrayOf(0x87, 0x86, 0x85, 0x84)
        for (i in hz.indices) {
            f.setOutputDataRate(hz[i])
            assertEquals(ctrl[i], mock.registers()[0x20])
            assertEquals(hz[i], f.getOutputDataRate(), 1e-9)
        }
        assertThrows(IllegalArgumentException::class.java) { f.setOutputDataRate(5.0) }
    }

    @Test
    fun averaging() {
        make()
        val f = Hts221Full(mock)
        for ((t, h, reg) in listOf(Triple(256, 512, 0x3F), Triple(2, 4, 0x00), Triple(16, 32, 0x1B))) {
            f.setAveraging(t, h)
            assertEquals(reg, mock.registers()[0x10])
            assertArrayEquals(intArrayOf(t, h), f.getAveraging())
        }
        for ((t, h) in listOf(3 to 32, 16 to 2, 1 to 32, 16 to 1024)) {
            assertThrows(IllegalArgumentException::class.java) { f.setAveraging(t, h) }
        }
    }

    @Test
    fun bduPowerHeaterDrdy() {
        make()
        val f = Hts221Full(mock)
        f.setBlockDataUpdate(false)
        assertEquals(0x80, mock.registers()[0x20])
        f.setBlockDataUpdate(true)
        f.powerDown()
        assertEquals(0x04, mock.registers()[0x20])
        f.powerUp()
        assertEquals(0x84, mock.registers()[0x20])
        f.setHeater(true)
        assertTrue(f.isHeaterOn())
        f.setHeater(false)
        assertFalse(f.isHeaterOn())
        f.configureDrdy(true, true, false)
        assertEquals(0x04, mock.registers()[0x22])
        f.configureDrdy(true, false, true)
        assertEquals(0xC4, mock.registers()[0x22])
        f.configureDrdy(false)
        assertEquals(0x00, mock.registers()[0x22])
    }

    @Test
    fun rebootRereadsCalibrationAndTimesOut() {
        make()
        val f = Hts221Full(mock)
        mock.setRegister(0xB0, 60)
        f.reboot()
        assertEquals(30.0, f.readCalibration()[0], 1e-9)
        mock.stuckBoot = true
        assertThrows(IOException::class.java) { f.reboot() }
    }

    @Test
    fun identityStatusAndDewPoint() {
        make()
        val f = Hts221Full(mock)
        assertEquals(0xBC, f.whoAmI())
        assertEquals(0x03, f.status())
        assertTrue(f.dataReady())
        assertEquals(0x03, f.pollInterrupt())
        val g = Math.log(0.30) + 17.62 * 15.0 / (243.12 + 15.0)
        assertEquals(243.12 * g / (17.62 - g), f.dewPoint(), 1e-9)
    }

    @Test
    fun interruptViaPin() {
        make()
        val pin = FakePin()
        mock.pin = pin
        val f = Hts221Full(mock)
        val got = AtomicInteger(-1)
        f.onInterrupt { got.set(it) }
        assertEquals(0x04, mock.registers()[0x22])
        assertEquals(EdgeTrigger.RISING, pin.trigger)
        pin.handler!!.onEdge()
        assertEquals(0x03, got.get())
        f.offInterrupt()
        assertNull(pin.handler)
        assertEquals(0x00, mock.registers()[0x22])
    }

    @Test
    fun interruptPollingFallback() {
        make()
        val f = Hts221Full(mock)
        val got = AtomicInteger(-1)
        f.onInterrupt { got.set(it) }
        val end = System.currentTimeMillis() + 1000
        while (got.get() < 0 && System.currentTimeMillis() < end) Thread.sleep(10)
        f.offInterrupt()
        assertEquals(0x03, got.get())
    }
}
