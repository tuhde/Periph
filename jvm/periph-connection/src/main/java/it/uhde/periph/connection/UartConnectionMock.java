package it.uhde.periph.connection;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Scripted TMC2208-style UART chip simulator for unit tests — no hardware.
 *
 * <p>Behaves like a Trinamic single-wire UART slave: 8-byte write datagrams
 * store the register and bump {@code IFCNT}; 4-byte read requests queue the
 * reply from {@link #regs()}. With {@code echo} every transmitted byte is also
 * queued back, as on the shared {@code PDN_UART} wire. Datagrams are recorded
 * in {@link #datagrams()}, register writes in {@link #regWrites()}.
 */
public class UartConnectionMock implements Connection, AvailableConnection {

    private final boolean echo;
    private final Map<Integer, Long> regs = new HashMap<>();
    private final List<byte[]> datagrams = new ArrayList<>();
    private final List<long[]> regWrites = new ArrayList<>();
    private final Deque<Byte> rx = new ArrayDeque<>();
    private boolean corruptNextReply = false;

    /** Mock with the shared-wire echo enabled. */
    public UartConnectionMock() {
        this(true);
    }

    /**
     * @param echo true to echo every transmitted byte back
     */
    public UartConnectionMock(boolean echo) {
        this.echo = echo;
        regs.put(0x06, 0x21000100L);
        regs.put(0x02, 0L);
    }

    /** CRC-8 (polynomial 0x07, init 0, LSB first) over the first {@code len} bytes. */
    public static int crc8(byte[] data, int len) {
        int crc = 0;
        for (int i = 0; i < len; i++) {
            int b = data[i] & 0xFF;
            for (int k = 0; k < 8; k++) {
                if ((((crc >> 7) ^ b) & 1) != 0) crc = ((crc << 1) ^ 0x07) & 0xFF;
                else crc = (crc << 1) & 0xFF;
                b >>= 1;
            }
        }
        return crc;
    }

    /** Register file returned on reads and updated by write datagrams. */
    public Map<Integer, Long> regs() { return regs; }

    /** Every datagram passed to {@link #write}. */
    public List<byte[]> datagrams() { return datagrams; }

    /** Every register write as {@code {register, value}}. */
    public List<long[]> regWrites() { return regWrites; }

    /** Corrupt the CRC of the next read reply. */
    public void corruptNextReply() { corruptNextReply = true; }

    /** Last value written to {@code reg}, or -1 if never written. */
    public long lastWrite(int reg) {
        long v = -1;
        for (long[] w : regWrites) if (w[0] == reg) v = w[1];
        return v;
    }

    @Override
    public void write(byte[] data) throws IOException {
        datagrams.add(data.clone());
        if (echo) for (byte b : data) rx.addLast(b);
        if (data.length == 8 && (data[2] & 0x80) != 0) {
            int reg = data[2] & 0x7F;
            long value = ((data[3] & 0xFFL) << 24) | ((data[4] & 0xFFL) << 16)
                    | ((data[5] & 0xFFL) << 8) | (data[6] & 0xFFL);
            regs.put(reg, value);
            regWrites.add(new long[] {reg, value});
            regs.put(0x02, (regs.get(0x02) + 1) & 0xFF);
        } else if (data.length == 4) {
            int reg = data[2] & 0x7F;
            long v = regs.getOrDefault(reg, 0L);
            byte[] body = {0x05, (byte) 0xFF, (byte) reg, (byte) (v >> 24), (byte) (v >> 16),
                    (byte) (v >> 8), (byte) v, 0};
            body[7] = (byte) crc8(body, 7);
            if (corruptNextReply) {
                body[7] ^= (byte) 0xFF;
                corruptNextReply = false;
            }
            for (byte b : body) rx.addLast(b);
        }
    }

    @Override
    public byte[] read(int n) throws IOException {
        byte[] out = new byte[n];
        for (int i = 0; i < n && !rx.isEmpty(); i++) out[i] = rx.removeFirst();
        return out;
    }

    @Override
    public byte[] writeRead(byte[] data, int n) throws IOException {
        write(data);
        return read(n);
    }

    @Override
    public int available() { return rx.size(); }

    @Override public void enable() {}
    @Override public void disable() {}
    @Override public boolean isEnabled() { return true; }
    @Override public InputPin intPin() { return null; }
    @Override public OutputPin enPin() { return null; }
    @Override public void close() {}
}
