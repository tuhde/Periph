package it.uhde.periph.chips.display

import it.uhde.periph.connection.Connection

/** I2C transport of the UC1611s: commands on the command address, RAM data (32-byte chunks) on the data address. */
class Uc1611sI2cBus(private val cmd: Connection, private val data: Connection) : Uc1611sBus {

    companion object {
        private const val NOP = 0xE3
        /** Largest data write in bytes (the 32-byte limit of some I2C layers). */
        const val CHUNK = 32
    }

    override fun writeCommand(bytes: ByteArray) {
        cmd.write(bytes)
    }

    override fun writeData(buf: ByteArray, off: Int, len: Int) {
        var i = 0
        while (i < len) {
            val n = minOf(CHUNK, len - i)
            data.write(buf.copyOfRange(off + i, off + i + n))
            i += n
        }
    }

    override fun canRead(): Boolean = true

    override fun readStatus(): ByteArray {
        cmd.write(byteArrayOf(NOP.toByte()))
        return cmd.read(3)
    }

    override fun readRam(n: Int): ByteArray = data.read(n + 1).copyOfRange(1, n + 1)
}
