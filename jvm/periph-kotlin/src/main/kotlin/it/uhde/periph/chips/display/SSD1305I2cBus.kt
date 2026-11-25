package it.uhde.periph.chips.display

import it.uhde.periph.connection.Connection

/** I2C transport of the SSD1305: every write starts with a control byte (0x00 = commands, 0x40 = RAM data). */
class SSD1305I2cBus(private val conn: Connection) : SSD1305Bus {

    companion object {
        /** Largest write in bytes including the control byte (the 32-byte limit of some I2C layers). */
        const val CHUNK = 32
    }

    private fun writeFramed(control: Int, buf: ByteArray, off: Int, len: Int) {
        var i = 0
        while (i < len) {
            val n = minOf(CHUNK - 1, len - i)
            val chunk = ByteArray(n + 1)
            chunk[0] = control.toByte()
            System.arraycopy(buf, off + i, chunk, 1, n)
            conn.write(chunk)
            i += n
        }
    }

    override fun writeCommands(bytes: ByteArray) = writeFramed(0x00, bytes, 0, bytes.size)

    override fun writeData(buf: ByteArray, off: Int, len: Int) = writeFramed(0x40, buf, off, len)

    override fun canRead(): Boolean = true

    override fun readStatus(): Int {
        conn.write(byteArrayOf(0x00))
        return conn.read(1)[0].toInt() and 0xFF
    }

    override fun readRam(n: Int): ByteArray {
        conn.write(byteArrayOf(0x40))
        return conn.read(n + 1).copyOfRange(1, n + 1)
    }
}
