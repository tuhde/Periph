package it.uhde.periph.chips.display

import groovy.transform.CompileStatic
import it.uhde.periph.connection.Connection

/** I2C transport of the SH1106: every write starts with a control byte (0x00 = commands, 0x40 = RAM data). */
@CompileStatic
final class SH1106I2cBus implements SH1106Bus {
    /** Largest write in bytes including the control byte (the 32-byte limit of some I2C layers). */
    static final int CHUNK = 32

    private final Connection conn

    /** @param connection connection at 0x3C or 0x3D */
    SH1106I2cBus(Connection connection) {
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

    @Override void writeCommands(byte[] bytes) throws IOException {
        writeFramed(0x00, bytes, 0, bytes.length)
    }

    @Override void writeData(byte[] buf, int off, int len) throws IOException {
        writeFramed(0x40, buf, off, len)
    }

    @Override boolean canRead() {
        return true
    }

    @Override void hardwareReset() throws IOException {
    }

    @Override int readStatus() throws IOException {
        conn.write([0x00] as byte[])
        return conn.read(1)[0] & 0xFF
    }

    @Override byte[] readRam(int n) throws IOException {
        conn.write([0x40] as byte[])
        byte[] raw = conn.read(n + 1)
        byte[] out = new byte[n]
        System.arraycopy(raw, 1, out, 0, n)
        return out
    }
}
