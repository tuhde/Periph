package it.uhde.periph.chips.accelerometer

import it.uhde.periph.connection.Connection
import it.uhde.periph.connection.InputPin
import it.uhde.periph.connection.OutputPin
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

private const val CMD_WRITE_REG = 0x0A
private const val CMD_READ_REG = 0x0B

/**
 * In-memory fake [Connection] for ADXL362 unit tests. ADXL362's SPI framing
 * is opcode+address(+data) -- WRITE 0x0A addr data, READ 0x0B addr -- unlike
 * `MockConnection`'s single-byte-is-the-address convention, so the real
 * target register is the SECOND byte of a write/writeRead command.
 */
private class Adxl362MockConnection : Connection {
    val registers = HashMap<Int, Int>()
    val writes = ArrayList<ByteArray>()

    fun setRegister(reg: Int, vararg values: Int) {
        values.forEachIndexed { i, v -> registers[reg + i] = v and 0xFF }
    }

    override fun enable() {}
    override fun disable() {}
    override fun isEnabled(): Boolean = true
    override fun intPin(): InputPin? = null
    override fun enPin(): OutputPin? = null

    override fun write(data: ByteArray) {
        writes.add(data.clone())
        if (data.size >= 3 && (data[0].toInt() and 0xFF) == CMD_WRITE_REG) {
            val reg = data[1].toInt() and 0xFF
            for (i in 2 until data.size) registers[reg + i - 2] = data[i].toInt() and 0xFF
        }
    }

    override fun read(n: Int): ByteArray = ByteArray(n)

    override fun writeRead(data: ByteArray, n: Int): ByteArray {
        writes.add(data.clone())
        val reg = when {
            data.size == 2 && (data[0].toInt() and 0xFF) == CMD_READ_REG -> data[1].toInt() and 0xFF
            data.isNotEmpty() -> data[0].toInt() and 0xFF
            else -> return ByteArray(n)
        }
        return ByteArray(n) { i -> (registers[reg + i] ?: 0).toByte() }
    }

    override fun close() {}
}

private fun newConnection(): Adxl362MockConnection {
    val c = Adxl362MockConnection()
    c.setRegister(0x00, 0xAD, 0x1D, 0xF2, 0x01) // DEVID_AD, DEVID_MST, PARTID, REVID
    return c
}

private fun lastWriteEquals(conn: Adxl362MockConnection, vararg bytes: Int): Boolean {
    val want = ByteArray(bytes.size) { bytes[it].toByte() }
    return conn.writes.last().contentEquals(want)
}

private fun writeAtEquals(conn: Adxl362MockConnection, fromEnd: Int, vararg bytes: Int): Boolean {
    val want = ByteArray(bytes.size) { bytes[it].toByte() }
    return conn.writes[conn.writes.size - fromEnd].contentEquals(want)
}

class Adxl362Test {

    @Test
    fun constructionAndRead() {
        val conn = newConnection()
        val chip = Adxl362Minimal(conn)
        assertTrue(writeAtEquals(conn, 2, 0x0A, 0x2C, 0x13))
        assertTrue(lastWriteEquals(conn, 0x0A, 0x2D, 0x02))

        conn.setRegister(0x0E, 0x64, 0x00, 0xCE, 0x0F, 0xD0, 0x07) // x=100,y=-50,z=2000 raw
        val xyz = chip.read()
        assertEquals(0.1, xyz[0], 1e-6)
        assertEquals(-0.05, xyz[1], 1e-6)
        assertEquals(2.0, xyz[2], 1e-6)
    }

    @Test
    fun deviceIdAndSoftReset() {
        val conn = newConnection()
        val full = Adxl362Full(conn)

        conn.setRegister(0x00, 0xAD, 0x1D, 0xF2, 0x07)
        assertArrayEquals(intArrayOf(0xAD, 0x1D, 0xF2, 0x07), full.deviceId())

        full.softReset()
        assertTrue(lastWriteEquals(conn, 0x0A, 0x1F, 0x52))
    }

    @Test
    fun setRangeAndSetOdr() {
        val conn = newConnection()
        val full = Adxl362Full(conn)

        conn.setRegister(0x2C, 0x13)
        full.setRange(4)
        assertTrue(lastWriteEquals(conn, 0x0A, 0x2C, 0x53))

        conn.setRegister(0x2C, 0x53)
        full.setRange(8)
        assertTrue(lastWriteEquals(conn, 0x0A, 0x2C, 0x93))

        conn.setRegister(0x2C, 0x93)
        full.setOdr(60.0f) // nearest of 50/100 -> 50 Hz (code 0x02)
        assertTrue(lastWriteEquals(conn, 0x0A, 0x2C, 0x92))
    }

    @Test
    fun read8bitTemperatureStatus() {
        val conn = newConnection()
        val full = Adxl362Full(conn)
        conn.setRegister(0x2C, 0x92)
        full.setRange(8)

        conn.setRegister(0x08, 100, 206, 50) // x=100, y=-50 (0xCE), z=50
        val r8 = full.read8bit()
        val sens8 = 0.004255 * 16.0
        assertEquals(100 * sens8, r8[0], 1e-3)
        assertEquals(-50 * sens8, r8[1], 1e-3)
        assertEquals(50 * sens8, r8[2], 1e-3)

        conn.setRegister(0x14, 0xAB, 0x01) // raw 427 = 30 C
        assertEquals(30.0, full.temperature(), 0.01)

        conn.setRegister(0x0B, 0x41) // AWAKE + DATA_READY
        assertEquals(0x41, full.status())
        assertTrue(full.awake())
        assertTrue(full.dataReady())
    }

    @Test
    fun fifoConfigureAndRead() {
        val conn = newConnection()
        val full = Adxl362Full(conn)

        conn.setRegister(0x0C, 0xFF, 0x01) // 0x1FF = 511
        assertEquals(0x1FF, full.fifoEntries())

        full.configureFifo(Adxl362Full.FIFO_STREAM, true, 300)
        assertTrue(writeAtEquals(conn, 2, 0x0A, 0x28, 0x0E))
        assertTrue(lastWriteEquals(conn, 0x0A, 0x29, 0x2C))

        conn.setRegister(0x0C, 2, 0) // 2 entries
        conn.setRegister(0x0D, 100, 0, 171, 193)
        val entries = full.readFifo()
        assertEquals(2, entries.size)
        assertEquals(Adxl362Full.AXIS_X.toDouble(), entries[0][0])
        assertEquals(100 * 0.001, entries[0][1], 1e-6)
        assertEquals(Adxl362Full.AXIS_TEMP.toDouble(), entries[1][0])
        assertEquals(30.0, entries[1][1], 0.01)
    }

    @Test
    fun activityThreshold11BitRegression() {
        // Regression: THRESH_ACT_H/THRESH_INACT_H are documented as bits
        // [10:8] (3 bits, 11-bit total threshold) -- must not clamp to
        // 10-bit (0x3FF) or mask the H byte with 0x03.
        val conn = newConnection()
        val full = Adxl362Full(conn)
        conn.setRegister(0x2C, 0x92)
        full.setRange(2)

        conn.setRegister(0x27, 0x00)
        full.setActivityThreshold(1.5, true)
        // raw = round(1.5 / 0.001) = 1500 = 0x5DC -> L=0xDC, H bits[10:8]=0x05.
        assertTrue(writeAtEquals(conn, 4, 0x0A, 0x20, 0xDC))
        assertTrue(writeAtEquals(conn, 3, 0x0A, 0x21, 0x05))
        assertTrue(lastWriteEquals(conn, 0x0A, 0x27, 0x02))
    }

    @Test
    fun linkLoopInterruptAndSelfTest() {
        val conn = newConnection()
        val full = Adxl362Full(conn)

        conn.setRegister(0x27, 0x05)
        full.setLinkLoopMode(Adxl362Full.LINKLOOP_LOOP)
        assertTrue(lastWriteEquals(conn, 0x0A, 0x27, 0x35))

        conn.setRegister(0x2A, 0x00)
        full.setInterrupt(1, Adxl362Full.SOURCE_AWAKE, true)
        assertTrue(lastWriteEquals(conn, 0x0A, 0x2A, 0x40))

        conn.setRegister(0x2E, 0x00)
        full.selfTest(true)
        assertTrue(lastWriteEquals(conn, 0x0A, 0x2E, 0x01))
    }
}
