package it.uhde.periph.chips.display;

import it.uhde.periph.connection.DisplayConn;

import java.io.IOException;

/**
 * 4-wire SPI transport of the SH1106 on a display connection: D/C low for every command byte, high for RAM data.
 * SPI is write-only.
 */
public final class SH1106SpiBus implements SH1106Bus {
    /** Largest data write in bytes on the SPI transport (one page). */
    public static final int CHUNK = 132;

    private final DisplayConn conn;

    /** @param connection display connection (CS, D/C = A0, RES optional, no BUSY) */
    public SH1106SpiBus(DisplayConn connection) {
        this.conn = connection;
    }

    @Override public void writeCommands(byte[] bytes) throws IOException {
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
