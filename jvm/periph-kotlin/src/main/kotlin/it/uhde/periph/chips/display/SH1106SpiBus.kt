package it.uhde.periph.chips.display

import it.uhde.periph.connection.DisplayConn

/**
 * 4-wire SPI transport of the SH1106 on a display connection: D/C low for every command byte, high for RAM data.
 * SPI is write-only.
 */
class SH1106SpiBus(private val conn: DisplayConn) : SH1106Bus {

    companion object {
        /** Largest data write in bytes on the SPI transport (one page). */
        const val CHUNK = 132
    }

    override fun writeCommands(bytes: ByteArray) {
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
