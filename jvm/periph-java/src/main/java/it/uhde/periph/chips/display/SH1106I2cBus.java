package it.uhde.periph.chips.display;

import it.uhde.periph.connection.Connection;

import java.io.IOException;

/** I2C transport of the SH1106: every write starts with a control byte (0x00 = commands, 0x40 = RAM data). */
public final class SH1106I2cBus implements SH1106Bus {
    /** Largest write in bytes including the control byte (the 32-byte limit of some I2C layers). */
    public static final int CHUNK = 32;

    private final Connection conn;

    /** @param connection connection at 0x3C or 0x3D */
    public SH1106I2cBus(Connection connection) {
        this.conn = connection;
    }

    private void writeFramed(int control, byte[] buf, int off, int len) throws IOException {
        for (int i = 0; i < len; i += CHUNK - 1) {
            int n = Math.min(CHUNK - 1, len - i);
            byte[] chunk = new byte[n + 1];
            chunk[0] = (byte) control;
            System.arraycopy(buf, off + i, chunk, 1, n);
            conn.write(chunk);
        }
    }

    @Override public void writeCommands(byte[] bytes) throws IOException {
        writeFramed(0x00, bytes, 0, bytes.length);
    }

    @Override public void writeData(byte[] buf, int off, int len) throws IOException {
        writeFramed(0x40, buf, off, len);
    }

    @Override public boolean canRead() {
        return true;
    }

    @Override public int readStatus() throws IOException {
        conn.write(new byte[] {0x00});
        return conn.read(1)[0] & 0xFF;
    }

    @Override public byte[] readRam(int n) throws IOException {
        conn.write(new byte[] {0x40});
        byte[] raw = conn.read(n + 1);
        byte[] out = new byte[n];
        System.arraycopy(raw, 1, out, 0, n);
        return out;
    }
}
