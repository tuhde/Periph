package it.uhde.periph.chips.display

import it.uhde.periph.connection.DisplayConn

/**
 * 4-wire SPI transport of the UC1611s on a display connection: D/C# low for every command byte (own CS window), high
 * for RAM data. The module's SPI is write-only and has no acknowledge, so nothing is detected or retried.
 */
class Uc1611sSpiBus(private val conn: DisplayConn) : Uc1611sBus {

    companion object {
        /** Largest data write in bytes on the SPI transport (one page). */
        const val CHUNK = 240
    }

    override fun writeCommand(bytes: ByteArray) {
        for (b in bytes) conn.sendCommand(b.toInt() and 0xFF, ByteArray(0))
    }

    override fun writeData(buf: ByteArray, off: Int, len: Int) {
        var i = 0
        while (i < len) {
            val n = minOf(CHUNK, len - i)
            conn.sendData(buf.copyOfRange(off + i, off + i + n))
            i += n
        }
    }

    override fun canRead(): Boolean = false

    override fun hardwareReset() {
        conn.reset()
    }
}
