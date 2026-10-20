package it.uhde.periph.chips.display;

import it.uhde.periph.connection.DisplayConn;

import java.io.IOException;

/**
 * 4-wire SPI transport of the UC1611s on a display connection: D/C# low for every command byte (own CS window),
 * high for RAM data. The module's SPI is write-only and has no acknowledge, so nothing is detected or retried.
 */
public final class Uc1611sSpiBus implements Uc1611sBus {
    /** Largest data write in bytes on the SPI transport (one page). */
    public static final int CHUNK = 240;

    private final DisplayConn conn;

    /** @param connection display connection (CS#, D/C# = CD, RES# = RST optional, no BUSY) */
    public Uc1611sSpiBus(DisplayConn connection) {
        this.conn = connection;
    }

    @Override public void writeCommand(byte[] bytes) throws IOException {
        for (byte b : bytes) conn.sendCommand(b & 0xFF, new byte[0]);
    }

    @Override public void writeData(byte[] buf, int off, int len) throws IOException {
        for (int i = 0; i < len; i += CHUNK) {
            int n = Math.min(CHUNK, len - i);
            byte[] chunk = new byte[n];
            System.arraycopy(buf, off + i, chunk, 0, n);
            conn.sendData(chunk);
        }
    }

    @Override public boolean canRead() {
        return false;
    }

    @Override public void hardwareReset() throws IOException {
        conn.reset();
    }
}
