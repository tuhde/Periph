package it.uhde.periph.chips.memory;

import it.uhde.periph.connection.Connection;
import it.uhde.periph.connection.InputPin;
import it.uhde.periph.connection.OutputPin;
import it.uhde.periph.storage.BlockDevice;
import it.uhde.periph.storage.BlockDeviceException;
import it.uhde.periph.storage.BlockDeviceException.Kind;
import it.uhde.periph.storage.RamBlockDevice;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Mx25l12845eTest {

    private static final int SIZE = Mx25l12845eMinimal.SIZE;

    /**
     * Behavioural MX25L12845E: 16 MiB array programmable 1 -&gt; 0 only, page wrap on PP, 4/32/64 KiB and
     * chip erase, WEL/WIP handling (WIP reads high for a few RDSR polls after every program/erase),
     * BP block protect with P_FAIL/E_FAIL, secured OTP, deep power-down, RES/REMS/RDID/SFDP.
     */
    private static class FlashModel implements Connection {
        final byte[] mem = new byte[SIZE];
        final byte[] otp = new byte[512];
        boolean inOtp, wel, dp, otpLocked, stuckBusy;
        int sr, scur = 0x01, busyPolls, busyAfterOp = 2;
        int pp, se, be32, be64, ce;
        byte[] id = {(byte) 0xC2, 0x20, 0x18};

        FlashModel() {
            Arrays.fill(mem, (byte) 0xFF);
            Arrays.fill(otp, (byte) 0xFF);
            for (int i = 0; i < 16; i++) otp[i] = (byte) (0xA0 + i);
        }

        private int protectedFrom() {
            int bp = (sr >> 2) & 0xF;
            if (bp == 0) return SIZE;
            if (bp >= 8) return 0;
            return SIZE - (65536 << bp);
        }

        private boolean isProtected(int a, int n) { return a + n > protectedFrom(); }

        private void startBusy() { busyPolls = busyAfterOp; wel = false; }

        @Override public void enable() {}
        @Override public void disable() {}
        @Override public boolean isEnabled() { return true; }
        @Override public InputPin intPin() { return null; }
        @Override public OutputPin enPin() { return null; }
        @Override public void close() {}
        @Override public byte[] read(int n) { return new byte[n]; }
        @Override public void write(byte[] d) { frame(d, 0); }
        @Override public byte[] writeRead(byte[] d, int n) { return frame(d, n); }

        private byte[] frame(byte[] d, int n) {
            int op = d[0] & 0xFF;
            byte[] out = new byte[n];
            if (dp && op != 0xAB) return out;
            boolean busy = stuckBusy || busyPolls > 0;
            if (busy && op != 0x05 && op != 0x2B && op != 0x9F && op != 0x30) return out;
            int a = d.length >= 4 ? ((d[1] & 0xFF) << 16) | ((d[2] & 0xFF) << 8) | (d[3] & 0xFF) : 0;
            switch (op) {
                case 0x06 -> wel = true;
                case 0x04 -> wel = false;
                case 0x9F -> { for (int i = 0; i < Math.min(n, 3); i++) out[i] = id[i]; }
                case 0x05 -> {
                    out[0] = (byte) ((sr & 0xFC) | (wel ? 2 : 0) | (busy ? 1 : 0));
                    if (busyPolls > 0) busyPolls--;
                }
                case 0x01 -> {
                    if (wel && d.length >= 2) { sr = d[1] & 0xFC; startBusy(); }
                }
                case 0x03, 0x0B, 0x5A -> {
                    for (int i = 0; i < n; i++) {
                        if (op == 0x5A) out[i] = (byte) (a + i);
                        else if (inOtp) out[i] = otp[(a + i) & 0x1FF];
                        else out[i] = mem[(a + i) & 0xFFFFFF];
                    }
                }
                case 0x02 -> {
                    if (wel) {
                        pp++;
                        if (inOtp) {
                            if (otpLocked) scur |= 0x20;
                            else for (int i = 4; i < d.length; i++) otp[(a + i - 4) & 0x1FF] &= d[i];
                        } else if (isProtected(a & ~255, 256)) {
                            scur |= 0x20;
                        } else {
                            int page = a & ~255;
                            for (int i = 4; i < d.length; i++) mem[page + ((a + i - 4) & 255)] &= d[i];
                        }
                        startBusy();
                    }
                }
                case 0x20, 0x52, 0xD8 -> {
                    if (wel) {
                        int size = op == 0x20 ? 4096 : op == 0x52 ? 32768 : 65536;
                        int base = a & ~(size - 1);
                        if (op == 0x20) se++; else if (op == 0x52) be32++; else be64++;
                        if (isProtected(base, size)) scur |= 0x40;
                        else Arrays.fill(mem, base, base + size, (byte) 0xFF);
                        startBusy();
                    }
                }
                case 0xC7, 0x60 -> {
                    if (wel) {
                        ce++;
                        if (((sr >> 2) & 0xF) != 0) scur |= 0x40;
                        else Arrays.fill(mem, (byte) 0xFF);
                        startBusy();
                    }
                }
                case 0xB9 -> dp = true;
                case 0xAB -> { dp = false; if (n > 0) out[0] = 0x17; }
                case 0x90 -> { if (n >= 2) { out[0] = (byte) 0xC2; out[1] = 0x17; } }
                case 0xB1 -> inOtp = true;
                case 0xC1 -> inOtp = false;
                case 0x2B -> out[0] = (byte) scur;
                case 0x2F -> {
                    if (wel) { scur |= 0x02; otpLocked = true; startBusy(); }
                }
                case 0x30 -> scur &= ~0x60;
                default -> { }
            }
            return out;
        }
    }

    private static Kind kind(org.junit.jupiter.api.function.Executable e) {
        return assertThrows(BlockDeviceException.class, e).kind();
    }

    @Test
    void init() throws IOException {
        FlashModel m = new FlashModel();
        Mx25l12845eMinimal f = new Mx25l12845eMinimal(m);
        assertArrayEquals(new byte[] {(byte) 0xC2, 0x20, 0x18}, f.jedecId());
        assertEquals(16 * 1024 * 1024, f.size());
        FlashModel wrong = new FlashModel();
        wrong.id = new byte[] {(byte) 0xC2, 0x20, 0x17};
        assertEquals(Kind.NOT_READY, kind(() -> new Mx25l12845eMinimal(wrong)));
        FlashModel stuck = new FlashModel();
        stuck.stuckBusy = true;
        assertEquals(Kind.TIMEOUT, kind(() -> new Mx25l12845eMinimal(stuck, 20)));
        FlashModel asleep = new FlashModel();
        asleep.dp = true;
        new Mx25l12845eMinimal(asleep);
        assertFalse(asleep.dp, "init releases deep power-down");
    }

    @Test
    void minimalWrite() throws IOException {
        FlashModel m = new FlashModel();
        Mx25l12845eMinimal f = new Mx25l12845eMinimal(m);
        byte[] pattern = new byte[600];
        for (int i = 0; i < pattern.length; i++) pattern[i] = (byte) (i * 7 + 3);
        f.writeBytes(0x1F0, pattern);
        assertArrayEquals(pattern, f.readBytes(0x1F0, 600));
        assertEquals(0, m.se, "blank flash: no erase needed");
        assertTrue(m.mem[0x1EF] == (byte) 0xFF && m.mem[0x1F0 + 600] == (byte) 0xFF);
        int ppBefore = m.pp;
        f.writeBytes(0x1F0, pattern);
        assertTrue(m.pp == ppBefore && m.se == 0, "identical rewrite programs and erases nothing");
        byte[] more = new byte[600];
        for (int i = 0; i < more.length; i++) more[i] = (byte) (pattern[i] & 0xF0);
        f.writeBytes(0x1F0, more);
        assertTrue(Arrays.equals(more, f.readBytes(0x1F0, 600)) && m.se == 0, "1->0 only rewrite needs no erase");
        byte[] ff = new byte[10];
        Arrays.fill(ff, (byte) 0xFF);
        f.writeBytes(0x1F0, ff);
        assertTrue(m.se == 1 && Arrays.equals(ff, f.readBytes(0x1F0, 10)), "0->1 needs a sector erase");
        assertArrayEquals(Arrays.copyOfRange(more, 16, 36), f.readBytes(0x200, 20), "rest of the sector survived the erase");
        byte[] big = new byte[9000];
        for (int i = 0; i < big.length; i++) big[i] = (byte) (i ^ (i >> 8));
        f.writeBytes(0x2FF0, big);
        assertArrayEquals(big, f.readBytes(0x2FF0, 9000));
        assertEquals(Kind.OUT_OF_RANGE, kind(() -> f.writeBytes(SIZE - 2, Arrays.copyOf(big, 5))));
        f.eraseSector(0x2000);
        assertEquals((byte) 0xFF, m.mem[0x2000]);
    }

    @Test
    void protection() throws IOException {
        FlashModel m = new FlashModel();
        Mx25l12845eFull f = new Mx25l12845eFull(m);
        assertEquals(0, f.protection());
        f.setProtection(8);
        assertEquals(8, f.protection());
        assertEquals(Kind.WRITE_PROTECTED, kind(() -> f.pageProgram(0x100, new byte[] {0})));
        assertEquals((byte) 0xFF, m.mem[0x100]);
        assertEquals(0, f.readSecurity() & 0x60, "fail flags cleared by the driver (CLSR)");
        assertEquals(Kind.WRITE_PROTECTED, kind(() -> f.eraseSector(0)));
        assertTrue(f.info().readOnly());
        assertEquals(Kind.OUT_OF_RANGE, kind(() -> f.setProtection(16)));
        f.setProtection(0);
        f.pageProgram(0x100, new byte[] {0});
        assertEquals(0, m.mem[0x100]);
    }

    @Test
    void rawFull() throws IOException {
        FlashModel m = new FlashModel();
        Mx25l12845eFull f = new Mx25l12845eFull(m);
        byte[] data = new byte[300];
        for (int i = 0; i < data.length; i++) data[i] = (byte) i;
        f.pageProgram(0x1000, Arrays.copyOf(data, 256));
        assertArrayEquals(Arrays.copyOf(data, 256), f.readBytes(0x1000, 256));
        assertEquals(Kind.OUT_OF_RANGE, kind(() -> f.pageProgram(0x10C0, Arrays.copyOf(data, 100))));
        assertEquals(Kind.OUT_OF_RANGE, kind(() -> f.pageProgram(0, Arrays.copyOf(data, 257))));
        assertArrayEquals(f.readBytes(0x1000, 256), f.fastRead(0x1000, 256));
        f.eraseBlock32k(0x8000);
        f.eraseBlock64k(0x10000);
        assertTrue(m.be32 == 1 && m.be64 == 1);
        f.chipErase();
        assertTrue(m.ce == 1 && m.mem[0x1000] == (byte) 0xFF);
        assertEquals(0x17, f.electronicId());
        assertArrayEquals(new int[] {0xC2, 0x17}, f.deviceIds());
        f.deepPowerDown();
        assertTrue(m.dp);
        f.releaseDeepPowerDown();
        assertFalse(m.dp);
        assertEquals(1, f.readSfdp(0, 8)[1]);
        assertFalse(f.isBusy());
        f.writeStatus(0x04);
        assertEquals(0x04, m.sr);
        f.writeStatus(0);
    }

    @Test
    void otp() throws IOException {
        FlashModel m = new FlashModel();
        Mx25l12845eFull f = new Mx25l12845eFull(m);
        byte[] esn = f.serialNumber();
        assertTrue(esn[0] == (byte) 0xA0 && esn[15] == (byte) 0xAF);
        assertFalse(m.inOtp, "OTP mode exited");
        f.programOtp(16, new byte[] {1, 2, 3, 4});
        assertArrayEquals(new byte[] {1, 2, 3, 4}, f.readOtp(16, 4));
        assertEquals(Kind.OUT_OF_RANGE, kind(() -> f.programOtp(8, new byte[] {1})));
        assertEquals(Kind.OUT_OF_RANGE, kind(() -> f.programOtp(510, new byte[] {1, 2, 3, 4})));
        f.lockOtp();
        assertTrue(m.otpLocked);
    }

    @Test
    void blockDevice() throws IOException {
        FlashModel m = new FlashModel();
        Mx25l12845eFull f = new Mx25l12845eFull(m);
        assertTrue(f.blockSize() == 512 && f.blockCount() == 32768 && f.eraseSize() == 4096 && f.needsErase());
        assertEquals("MX25L12845E", f.info().name());
        byte[] w = new byte[1024];
        for (int k = 0; k < w.length; k++) w[k] = (byte) (k * 5 + 1);
        f.write(8, w);
        assertArrayEquals(w, f.read(8, 2));
        f.write(8, w);
        assertArrayEquals(w, f.read(8, 2), "identical rewrite is fine");
        byte[] ff = new byte[512];
        Arrays.fill(ff, (byte) 0xFF);
        assertEquals(Kind.NOT_ERASED, kind(() -> f.write(8, ff)));
        assertEquals(0, m.se, "NOT_ERASED did not erase anything");
        f.erase(8, 8);
        assertEquals((byte) 0xFF, m.mem[8 * 512]);
        assertEquals(Kind.OUT_OF_RANGE, kind(() -> f.erase(9, 8)));
        assertEquals(Kind.OUT_OF_RANGE, kind(() -> f.erase(32768 - 8, 16)));
        assertEquals(Kind.OUT_OF_RANGE, kind(() -> f.read(32768, 1)));
        f.sync();
        int se0 = m.se, be640 = m.be64, be320 = m.be32;
        f.erase(0, 128 + 64 + 8);
        assertTrue(m.be64 - be640 == 1 && m.be32 - be320 == 1 && m.se - se0 == 1, "erase picks 64K, then 32K, then 4K");
        FlashModel m2 = new FlashModel();
        Mx25l12845eFull g = new Mx25l12845eFull(m2, true);
        g.write(0, w);
        g.write(0, ff);
        assertTrue(m2.se == 1 && Arrays.equals(ff, g.read(0, 1)), "auto-erase handles 0->1");
        assertArrayEquals(Arrays.copyOfRange(w, 512, 1024), g.read(1, 1), "neighbouring block kept");
        RamBlockDevice ram = new RamBlockDevice(512, 64, 8, false);
        byte[] x = new byte[512];
        Arrays.fill(x, (byte) 0x5A);
        for (BlockDevice dev : new BlockDevice[] {g, ram}) {
            long blk = dev == g ? 64 : 0;
            dev.erase(blk, 8);
            dev.write(blk, x);
            assertArrayEquals(x, dev.read(blk, 1));
            assertEquals(Kind.OUT_OF_RANGE, kind(() -> dev.read(dev.blockCount(), 1)));
        }
    }

    @Test
    void timeout() throws IOException {
        FlashModel m = new FlashModel();
        Mx25l12845eFull f = new Mx25l12845eFull(m);
        m.stuckBusy = true;
        assertEquals(Kind.TIMEOUT, kind(() -> f.waitUntilReady(5)));
        assertTrue(f.isBusy());
    }
}
