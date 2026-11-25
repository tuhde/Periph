package it.uhde.periph.chips.display

import groovy.transform.CompileStatic
import it.uhde.periph.connection.DisplayConn

/**
 * 4-wire SPI transport of the UC1611s on a display connection: D/C# low for every command byte (own CS window),
 * high for RAM data. The module's SPI is write-only and has no acknowledge, so nothing is detected or retried.
 */
@CompileStatic
class Uc1611sSpiBus implements Uc1611sBus {

    /** Largest data write in bytes on the SPI transport (one page). */
    static final int CHUNK = 240

    private final DisplayConn conn

    Uc1611sSpiBus(DisplayConn connection) {
        this.conn = connection
    }

    @Override void writeCommand(byte[] bytes) throws IOException {
        for (byte b : bytes) conn.sendCommand(b & 0xFF, new byte[0])
    }

    @Override void writeData(byte[] buf, int off, int len) throws IOException {
        for (int i = 0; i < len; i += CHUNK) {
            int n = Math.min(CHUNK, len - i)
            byte[] chunk = new byte[n]
            System.arraycopy(buf, off + i, chunk, 0, n)
            conn.sendData(chunk)
        }
    }

    @Override boolean canRead() { false }

    @Override byte[] readStatus() throws IOException {
        throw new UnsupportedOperationException('the SPI interface of the module is write-only')
    }

    @Override byte[] readRam(int n) throws IOException {
        throw new UnsupportedOperationException('the SPI interface of the module is write-only')
    }

    @Override void hardwareReset() throws IOException {
        conn.reset()
    }
}
