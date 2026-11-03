package it.uhde.periph.chips.display;

import it.uhde.periph.connection.Connection;

import java.io.IOException;

/** I2C transport of the UC1611s: commands on the command address, RAM data (32-byte chunks) on the data address. */
public final class Uc1611sI2cBus implements Uc1611sBus {
    private static final int NOP = 0xE3;
    /** Largest data write in bytes (the 32-byte limit of some I2C layers). */
    public static final int CHUNK = 32;

    private final Connection cmd;
    private final Connection data;

    /**
     * @param command connection at the command address
     * @param data    connection at the data address (command address + 1)
     */
    public Uc1611sI2cBus(Connection command, Connection data) {
        this.cmd = command;
        this.data = data;
    }

    @Override public void writeCommand(byte[] bytes) throws IOException {
        cmd.write(bytes);
    }

    @Override public void writeData(byte[] buf, int off, int len) throws IOException {
        for (int i = 0; i < len; i += CHUNK) {
            int n = Math.min(CHUNK, len - i);
            byte[] chunk = new byte[n];
            System.arraycopy(buf, off + i, chunk, 0, n);
            data.write(chunk);
        }
    }

    @Override public boolean canRead() {
        return true;
    }

    @Override public byte[] readStatus() throws IOException {
        cmd.write(new byte[] {(byte) NOP});
        return cmd.read(3);
    }

    @Override public byte[] readRam(int n) throws IOException {
        byte[] raw = data.read(n + 1);
        byte[] out = new byte[n];
        System.arraycopy(raw, 1, out, 0, n);
        return out;
    }
}
