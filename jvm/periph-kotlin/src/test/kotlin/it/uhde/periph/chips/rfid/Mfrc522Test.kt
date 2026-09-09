package it.uhde.periph.chips.rfid

import it.uhde.periph.connection.MockConnection
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.IOException
import java.util.ArrayDeque
import java.util.Deque

// Register addresses (protected in Mfrc522Minimal's companion, so not visible
// here across the Java-vs-Kotlin `protected` semantics gap - Kotlin `protected`
// is subclass-only, no same-package access like Java's). Mirrored here, same as
// the Python reference test does with its own module-private constants.
private const val REG_COMMAND     = 0x01
private const val REG_COM_IRQ     = 0x04
private const val REG_DIV_IRQ     = 0x05
private const val REG_ERROR       = 0x06
private const val REG_STATUS_2    = 0x08
private const val REG_FIFO_DATA   = 0x09
private const val REG_FIFO_LEVEL  = 0x0A
private const val REG_MODE        = 0x11
private const val REG_TX_CONTROL  = 0x14
private const val REG_TX_ASK      = 0x15
private const val REG_RF_CFG      = 0x26
private const val REG_T_MODE      = 0x2A
private const val REG_T_PRESCALER = 0x2B
private const val REG_T_RELOAD_H  = 0x2C
private const val REG_T_RELOAD_L  = 0x2D
private const val REG_VERSION     = 0x37

// SPI addressing: write = (reg<<1)&0x7E, read = write|0x80.
private fun waddr(reg: Int): Int = (reg shl 1) and 0x7E
private fun raddr(reg: Int): Int = waddr(reg) or 0x80

class Mfrc522Test {

    private fun writesAt(connection: MockConnection, addr: Int): List<ByteArray> =
        connection.writes().filter { it.size == 2 && (it[0].toInt() and 0xFF) == addr }

    private fun lastByte(writes: List<ByteArray>): Byte = writes.last()[1]

    /**
     * MockConnection plus a FIFO queue: MFRC522 reads FIFO_LEVEL then FIFO_DATA
     * one byte at a time via writeRead(), which the base register-map mock can't
     * model on its own (a plain register always returns the same fixed byte, but
     * FIFO_LEVEL/FIFO_DATA must reflect "how many bytes are left in this
     * response" across several transceive rounds in one call, e.g. readUid()'s
     * REQA -> anticollision -> select -> halt sequence).
     *
     * queueFifo(chunk) queues one whole response as a unit. FIFO_LEVEL reads
     * report the current front chunk's remaining length; FIFO_DATA reads pop one
     * byte from it, and the chunk is dropped once drained so the next queued
     * response becomes visible to the next FIFO_LEVEL read.
     */
    private class FifoAwareMockConnection : MockConnection() {
        private val fifoChunks: Deque<Deque<Int>> = ArrayDeque()

        fun queueFifo(vararg chunk: Int) {
            val q: Deque<Int> = ArrayDeque()
            for (b in chunk) q.addLast(b)
            fifoChunks.addLast(q)
        }

        @Throws(IOException::class)
        override fun writeRead(data: ByteArray, n: Int): ByteArray {
            val addr = data[0].toInt() and 0xFF
            if (addr == raddr(REG_FIFO_LEVEL) && n == 1) {
                writes().add(data.clone())
                val level = fifoChunks.peekFirst()?.size ?: 0
                return byteArrayOf(level.toByte())
            }
            if (addr == raddr(REG_FIFO_DATA) && n == 1) {
                writes().add(data.clone())
                if (fifoChunks.isEmpty()) return byteArrayOf(0)
                val front = fifoChunks.peekFirst()
                val b = front.pollFirst()
                if (front.isEmpty()) fifoChunks.pollFirst()
                return byteArrayOf(b.toByte())
            }
            return super.writeRead(data, n)
        }
    }

    @Test
    fun initSequence() {
        val connection = FifoAwareMockConnection()
        Mfrc522Full(connection)

        assertEquals(0x0F.toByte(), writesAt(connection, waddr(REG_COMMAND))[0][1])
        assertEquals(0x80.toByte(), lastByte(writesAt(connection, waddr(REG_T_MODE))))
        assertEquals(0xA9.toByte(), lastByte(writesAt(connection, waddr(REG_T_PRESCALER))))
        assertEquals(0x03.toByte(), lastByte(writesAt(connection, waddr(REG_T_RELOAD_H))))
        assertEquals(0xE8.toByte(), lastByte(writesAt(connection, waddr(REG_T_RELOAD_L))))
        assertEquals(0x40.toByte(), lastByte(writesAt(connection, waddr(REG_TX_ASK))))
        assertEquals(0x3D.toByte(), lastByte(writesAt(connection, waddr(REG_MODE))))
        assertEquals(0x03, (connection.registers()[waddr(REG_TX_CONTROL)] ?: 0) and 0x03)
    }

    @Test
    fun isCardPresentTrue() {
        val connection = FifoAwareMockConnection()
        val sensor = Mfrc522Full(connection)
        connection.setRegister(raddr(REG_COM_IRQ), 0x30)
        connection.setRegister(raddr(REG_ERROR), 0x00)
        connection.queueFifo(0x04, 0x00)

        assertTrue(sensor.isCardPresent())
    }

    @Test
    fun isCardPresentFalse() {
        val connection = FifoAwareMockConnection()
        val sensor = Mfrc522Full(connection)
        connection.setRegister(raddr(REG_COM_IRQ), 0x01) // TimerIRq only -> no card

        assertFalse(sensor.isCardPresent())
    }

    @Test
    fun readUidHappyPath() {
        val connection = FifoAwareMockConnection()
        val sensor = Mfrc522Full(connection)
        val uidBytes = intArrayOf(0x12, 0x34, 0x56, 0x78)
        var bcc = 0
        for (b in uidBytes) bcc = bcc xor b

        connection.setRegister(raddr(REG_COM_IRQ), 0x30)
        connection.setRegister(raddr(REG_ERROR), 0x00)
        connection.queueFifo(0x04, 0x00) // REQA response (isCardPresent(), called first by readUid())
        connection.queueFifo(uidBytes[0], uidBytes[1], uidBytes[2], uidBytes[3], bcc) // anticollision CL1
        connection.queueFifo(0x00) // select CL1 SAK: completion bit clear, single-size UID
        // HLTA (halt) result is ignored by the driver - no response bytes needed

        val uid = sensor.readUid()
        val expected = ByteArray(4) { uidBytes[it].toByte() }
        assertArrayEquals(expected, uid)
    }

    @Test
    fun readUidNoCard() {
        val connection = FifoAwareMockConnection()
        val sensor = Mfrc522Full(connection)
        connection.setRegister(raddr(REG_COM_IRQ), 0x01) // TimerIRq only -> no card

        assertNull(sensor.readUid())
    }

    @Test
    fun antennaControl() {
        val connection = FifoAwareMockConnection()
        val sensor = Mfrc522Full(connection)

        sensor.antennaOff()
        assertEquals(0, connection.registers()[waddr(REG_TX_CONTROL)]!! and 0x03)
        sensor.antennaOn()
        assertEquals(0x03, connection.registers()[waddr(REG_TX_CONTROL)]!! and 0x03)

        sensor.setAntennaGain(38)
        assertEquals(0x50, connection.registers()[waddr(REG_RF_CFG)]!! and 0x70)

        connection.setRegister(raddr(REG_RF_CFG), 0x60)
        assertEquals(43, sensor.antennaGain())
    }

    @Test
    fun setAntennaGainInvalidThrows() {
        val connection = FifoAwareMockConnection()
        val sensor = Mfrc522Full(connection)

        assertThrows(IllegalArgumentException::class.java) { sensor.setAntennaGain(99) }
    }

    @Test
    fun version() {
        val connection = FifoAwareMockConnection()
        val sensor = Mfrc522Full(connection)
        connection.setRegister(raddr(REG_VERSION), 0x92) // chipType=9, version=2

        assertArrayEquals(intArrayOf(9, 2), sensor.version())
    }

    @Test
    fun selfTestPass() {
        val connection = FifoAwareMockConnection()
        val sensor = Mfrc522Full(connection)
        connection.setRegister(raddr(REG_VERSION), 0x91) // version=1 -> v1.0 reference table
        val refV10 = intArrayOf(
            0x00, 0x87, 0x98, 0x0F, 0x49, 0xFF, 0x07, 0x19,
            0xBF, 0x22, 0x30, 0x49, 0x59, 0x63, 0xAD, 0xCA,
            0x7F, 0xE3, 0x4E, 0x03, 0x5C, 0x4E, 0x49, 0x50,
            0x47, 0x9A, 0x37, 0x61, 0xE7, 0xE2, 0xC6, 0x2E,
            0x75, 0x5A, 0xED, 0x04, 0x3D, 0x02, 0x4B, 0x78,
            0x32, 0xFF, 0x58, 0x3B, 0x7C, 0xE9, 0x00, 0x94,
            0xB4, 0x4A, 0x59, 0x5B, 0xFD, 0xC9, 0x29, 0xDF,
            0x35, 0x96, 0x98, 0x9E, 0x4F, 0x30, 0x32, 0x8D
        )
        connection.queueFifo(*refV10)

        assertTrue(sensor.selfTest())
    }

    @Test
    fun authenticateAndStopCrypto() {
        val connection = FifoAwareMockConnection()
        val sensor = Mfrc522Full(connection)
        val uid = byteArrayOf(0x12, 0x34, 0x56, 0x78)
        connection.setRegister(raddr(REG_STATUS_2), 0x08) // MFCrypto1On set immediately

        assertTrue(sensor.authenticate(4, Mfrc522Full.KEY_A, ByteArray(6) { 0xFF.toByte() }, uid))

        sensor.stopCrypto()
        assertEquals(0, connection.registers()[waddr(REG_STATUS_2)]!! and 0x08)
    }

    @Test
    fun authenticateBadKeyLength() {
        val connection = FifoAwareMockConnection()
        val sensor = Mfrc522Full(connection)
        val uid = byteArrayOf(0x12, 0x34, 0x56, 0x78)

        assertFalse(sensor.authenticate(4, Mfrc522Full.KEY_A, ByteArray(5) { 0xFF.toByte() }, uid))
    }

    @Test
    fun readBlock() {
        val connection = FifoAwareMockConnection()
        val sensor = Mfrc522Full(connection)
        val blockData = ByteArray(16) { it.toByte() }
        // calcCrc() polls DIV_IRQ; make it show CRCIRq set immediately, and preload
        // CRC_RESULT_H/L with a fixed placeholder - the driver just forwards
        // whatever the chip returns as the trailing 2 command bytes.
        connection.setRegister(raddr(REG_DIV_IRQ), 0x04)
        connection.setRegister(raddr(0x21), 0xAB) // CRC_RESULT_H
        connection.setRegister(raddr(0x22), 0xCD) // CRC_RESULT_L
        connection.setRegister(raddr(REG_COM_IRQ), 0x30)
        connection.setRegister(raddr(REG_ERROR), 0x00)
        connection.queueFifo(0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15)

        assertArrayEquals(blockData, sensor.readBlock(4))
    }

    @Test
    fun writeBlock() {
        val connection = FifoAwareMockConnection()
        val sensor = Mfrc522Full(connection)
        val blockData = ByteArray(16) { it.toByte() }
        connection.setRegister(raddr(REG_DIV_IRQ), 0x04)
        connection.setRegister(raddr(0x21), 0xAB)
        connection.setRegister(raddr(0x22), 0xCD)
        connection.setRegister(raddr(REG_COM_IRQ), 0x30)
        connection.setRegister(raddr(REG_ERROR), 0x00)
        connection.queueFifo(0x0A) // phase 1 ACK (0x0A in low nibble)
        connection.queueFifo(0x0A) // phase 2 ACK

        assertTrue(sensor.writeBlock(4, blockData))
    }
}
