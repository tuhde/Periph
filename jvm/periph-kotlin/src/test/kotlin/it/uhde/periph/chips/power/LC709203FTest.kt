package it.uhde.periph.chips.power

import it.uhde.periph.connection.MockConnection
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.IOException

class LC709203FTest {

    /** Each command code holds one (low, high, crc) word; writes do not overlap neighbours. */
    class WordMock : MockConnection() {
        val words = HashMap<Int, ByteArray>()

        fun put(cmd: Int, value: Int) {
            val lo = value and 0xFF
            val hi = (value shr 8) and 0xFF
            words[cmd] = byteArrayOf(lo.toByte(), hi.toByte(), LC709203FMinimal.crc8(0x16, cmd, 0x17, lo, hi).toByte())
        }

        @Throws(IOException::class)
        override fun write(data: ByteArray) {
            writes().add(data.clone())
            if (data.size == 4) words[data[0].toInt() and 0xFF] = data.copyOfRange(1, 4)
        }

        @Throws(IOException::class)
        override fun writeRead(data: ByteArray, n: Int): ByteArray {
            writes().add(data.clone())
            return words[data[0].toInt() and 0xFF]?.clone() ?: ByteArray(n)
        }

        fun wrote(cmd: Int, lo: Int, hi: Int) = writes().any {
            it.size == 4 && (it[0].toInt() and 0xFF) == cmd && (it[1].toInt() and 0xFF) == lo && (it[2].toInt() and 0xFF) == hi
        }

        fun lastIs(cmd: Int, lo: Int, hi: Int): Boolean {
            val w = writes().last()
            return w.size == 4 && (w[0].toInt() and 0xFF) == cmd && (w[1].toInt() and 0xFF) == lo &&
                (w[2].toInt() and 0xFF) == hi && (w[3].toInt() and 0xFF) == LC709203FMinimal.crc8(0x16, cmd, lo, hi)
        }
    }

    @Test
    fun crcDatasheetExamples() {
        assertEquals(0x86, LC709203FMinimal.crc8(0x16, 0x09, 0x17, 0xC2, 0x0E))
        assertEquals(0x3B, LC709203FMinimal.crc8(0x16, 0x09, 0x55, 0xAA))
    }

    @Test
    fun apaInterpolation() {
        assertEquals(0x14, LC709203FMinimal.apaFor(700))
        assertEquals(0x08, LC709203FMinimal.apaFor(50))
        assertEquals(0x36, LC709203FMinimal.apaFor(9000))
        assertEquals(0x19, LC709203FMinimal.apaFor(1000))
    }

    @Test
    fun fullApi() {
        val conn = WordMock()
        conn.put(0x13, 8)
        conn.put(0x14, 0)
        val sensor = LC709203FFull(conn)

        assertTrue(conn.wrote(0x15, 0x01, 0x00))
        assertTrue(conn.wrote(0x0B, 0x19, 0x00))
        assertTrue(conn.wrote(0x12, 0x01, 0x00))
        assertTrue(conn.wrote(0x16, 0x00, 0x00))
        assertTrue(conn.wrote(0x08, 0xA6, 0x0B))

        conn.put(0x09, 3778)
        assertEquals(3.778, sensor.voltage(), 1e-9)
        conn.put(0x0F, 755)
        assertEquals(75.5, sensor.percent(), 1e-9)
        conn.put(0x08, 0x0BA6)
        assertEquals(25.0, sensor.temperature(), 0.06)

        conn.words[0x09] = byteArrayOf(0xC2.toByte(), 0x0E, 0x00)
        assertThrows(IOException::class.java) { sensor.voltage() }

        sensor.setTemperature(100.0)
        assertTrue(conn.lastIs(0x08, 0x04, 0x0D))
        sensor.setTemperature(-50.0)
        assertTrue(conn.lastIs(0x08, 0xE4, 0x09))

        conn.put(0x0D, 42)
        assertEquals(42, sensor.rsoc())
        sensor.setRsoc(55)
        assertTrue(conn.lastIs(0x0D, 55, 0))
        sensor.initRsoc()
        assertTrue(conn.lastIs(0x07, 0x55, 0xAA))
        sensor.beforeRsoc()
        assertTrue(conn.lastIs(0x04, 0x55, 0xAA))

        sensor.useThermistor(3380)
        assertTrue(conn.wrote(0x06, 0x34, 0x0D) && conn.lastIs(0x16, 1, 0))
        conn.put(0x16, 1)
        assertTrue(sensor.isThermistorMode())
        sensor.useI2cTemperature(25.0)
        assertTrue(conn.lastIs(0x08, 0xA6, 0x0B))

        sensor.setCurrentDirection(LC709203FFull.DIRECTION_DISCHARGE)
        assertTrue(conn.lastIs(0x0A, 0xFF, 0xFF))
        assertThrows(IllegalArgumentException::class.java) { sensor.setCurrentDirection(5) }

        sensor.setApa(0x2D)
        assertTrue(conn.lastIs(0x0B, 0x2D, 0))
        sensor.setApt(0x1234)
        assertTrue(conn.lastIs(0x0C, 0x34, 0x12))
        sensor.setProfile(0)
        assertTrue(conn.wrote(0x12, 0, 0) && conn.lastIs(0x15, 1, 0))

        conn.put(0x1A, 0x0301)
        assertEquals(0x0301, sensor.profileId())
        conn.put(0x11, 0x1234)
        assertEquals(0x1234, sensor.icVersion())

        sensor.sleep()
        assertTrue(conn.lastIs(0x15, 2, 0))
        conn.put(0x15, 2)
        assertTrue(sensor.isSleeping())
        conn.put(0x15, 1)
        assertFalse(sensor.isSleeping())

        sensor.setAlarmRsoc(20)
        sensor.setAlarmVoltage(3.5)
        assertTrue(conn.lastIs(0x14, 0xAC, 0x0D))
        conn.put(0x0D, 10)
        conn.put(0x09, 3400)
        assertEquals(3, sensor.pollInterrupt())
        conn.put(0x0D, 50)
        conn.put(0x09, 3900)
        assertEquals(0, sensor.pollInterrupt())
    }
}
