package it.uhde.periph.chips.display

import groovy.transform.CompileStatic

import it.uhde.periph.connection.Connection

import java.io.IOException

/** I2C transport: every transaction starts with a control byte (0x00 = commands, 0x40 = RAM data). */
@CompileStatic
final class MonoOledI2cBus implements MonoOledBus {

    /** Largest write in bytes including the control byte (the 32-byte limit of some I2C layers). */
    static final int CHUNK = 32

    private final Connection conn

    /**
     * Create the transport.
     *
     * @param connection connection at 0x3C or 0x3D
     */
    MonoOledI2cBus(Connection connection) {
        this.conn = connection
    }

    private void writeFramed(int control, byte[] buf, int off, int len) throws IOException {
        for (int i = 0; i < len; i += CHUNK - 1) {
            int n = Math.min(CHUNK - 1, len - i)
            byte[] chunk = new byte[n + 1]
            chunk[0] = (byte) control
            System.arraycopy(buf, off + i, chunk, 1, n)
            conn.write(chunk)
        }
    }

    @Override public void writeCommands(byte[] bytes) throws IOException {
        writeFramed(0x00, bytes, 0, bytes.length)
    }

    @Override public void writeData(byte[] buf, int off, int len) throws IOException {
        writeFramed(0x40, buf, off, len)
    }
}
