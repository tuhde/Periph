package it.uhde.periph.chips.display

import groovy.transform.CompileStatic
import it.uhde.periph.connection.DisplayConn

/**
 * 4-wire SPI transport of the SSD1305 on a display connection: D/C low for every command byte, high for RAM data.
 * SPI is write-only.
 */
@CompileStatic
final class SSD1305SpiBus implements SSD1305Bus {
    /** Largest data write in bytes on the SPI transport (one page). */
    static final int CHUNK = 132

    private final DisplayConn conn

    /** @param connection display connection (CS, D/C = A0, RES optional, no BUSY) */
    SSD1305SpiBus(DisplayConn connection) {
        this.conn = connection
    }

    @Override void writeCommands(byte[] bytes) throws IOException {
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

    @Override boolean canRead() {
        return false
    }

    @Override int readStatus() throws IOException {
        throw new UnsupportedOperationException('read-back is not available on this transport')
    }

    @Override byte[] readRam(int n) throws IOException {
        throw new UnsupportedOperationException('read-back is not available on this transport')
    }

    @Override void hardwareReset() throws IOException {
        conn.reset()
    }
}
