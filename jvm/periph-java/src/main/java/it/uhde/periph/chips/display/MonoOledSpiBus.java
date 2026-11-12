package it.uhde.periph.chips.display;

import it.uhde.periph.connection.DisplayConn;

import java.io.IOException;

/** 4-wire SPI transport on a display connection: D/C low for every command byte, high for RAM data. */
public final class MonoOledSpiBus implements MonoOledBus {

    /** Largest data write in bytes (one page). */
    public static final int CHUNK = 128;

    private final DisplayConn conn;

    /**
     * Create the transport.
     *
     * @param connection display connection (D/C required, RES optional, no BUSY)
     */
    public MonoOledSpiBus(DisplayConn connection) {
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

    @Override public void hardwareReset() throws IOException {
        conn.reset();
    }
}
