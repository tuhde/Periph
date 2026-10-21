package it.uhde.periph.chips.memory;

import it.uhde.periph.chips.memory.SdSpiMinimal.CardType;
import it.uhde.periph.connection.InputPin;
import it.uhde.periph.connection.OutputPin;
import it.uhde.periph.connection.SPIExtensions;
import it.uhde.periph.storage.BlockDevice;
import it.uhde.periph.storage.BlockDeviceException;
import it.uhde.periph.storage.BlockDeviceException.Kind;
import it.uhde.periph.storage.RamBlockDevice;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SdSpiTest {

    /**
     * Behavioural model of an SD card in SPI mode behind {@link SPIExtensions}. Needs 80 idle
     * clocks before CMD0, checks the command CRC7, models R1/R3/R7, CSD/CID, single and
     * multi-block transfers with tokens, busy and CRC16.
     */
    private static class SdModel implements SPIExtensions {
        final boolean v2, sdhc;
        int csizeV2 = 7;               // (7+1) * 512 KiB = 4 MiB = 8192 blocks
        int acmd41Calls = 0, acmd41Needed = 3;
        boolean gotIdleClocks, selected, inIdle, crcOn, corruptReadCrc;
        int speed;
        int csTogglesInCommand;
        final byte[] mem = new byte[8192 * 512];
        final List<Integer> cmdLog = new ArrayList<>();
        int state;                     // 0 CMD, 1 WTOKEN, 2 WDATA
        final List<Integer> cmdBuf = new ArrayList<>();
        final Deque<Integer> out = new ArrayDeque<>();
        boolean appNext, streaming, multiWrite;
        long streamBlock, wBlock;
        final List<Integer> wData = new ArrayList<>();

        SdModel(boolean v2, boolean sdhc) { this.v2 = v2; this.sdhc = sdhc; }

        private void pushBlock(byte[] data, int off, int len) {
            out.add(0xFE);
            for (int i = 0; i < len; i++) out.add(data[off + i] & 0xFF);
            int c = SdSpiMinimal.crc16(data, off, len);
            if (corruptReadCrc) c ^= 1;
            out.add(c >> 8);
            out.add(c & 0xFF);
        }

        private long blockOf(int arg) { return sdhc ? (arg & 0xFFFFFFFFL) : (arg & 0xFFFFFFFFL) / 512; }

        private void doCommand() {
            boolean wasApp = appNext;
            appNext = false;
            byte[] cb = new byte[6];
            for (int i = 0; i < 6; i++) cb[i] = (byte) (int) cmdBuf.get(i);
            int cmd = cb[0] & 0x3F;
            int arg = ((cb[1] & 0xFF) << 24) | ((cb[2] & 0xFF) << 16) | ((cb[3] & 0xFF) << 8) | (cb[4] & 0xFF);
            cmdLog.add(wasApp ? (0x80 | cmd) : cmd);
            if (((cb[5] & 0xFF) >> 1) != SdSpiMinimal.crc7(cb, 5)) { out.add(0xFF); out.add(0x09); return; }
            out.add(0xFF);   // Ncr gap
            int idle = inIdle ? 0x01 : 0x00;
            if (wasApp) {
                if (cmd == 41) {
                    acmd41Calls++;
                    if (acmd41Calls >= acmd41Needed) { inIdle = false; out.add(0x00); } else out.add(0x01);
                } else out.add(idle);
                return;
            }
            switch (cmd) {
                case 0 -> {
                    if (!gotIdleClocks) { out.clear(); return; }
                    inIdle = true;
                    out.add(0x01);
                }
                case 8 -> {
                    if (!v2) out.add(0x05);
                    else { out.add(0x01); out.add(0); out.add(0); out.add(0x01); out.add(0xAA); }
                }
                case 9 -> {
                    byte[] csd = new byte[16];
                    if (sdhc) {
                        csd[0] = 0x40; csd[7] = (byte) ((csizeV2 >> 16) & 0x3F); csd[8] = (byte) (csizeV2 >> 8); csd[9] = (byte) csizeV2;
                    } else {
                        csd[5] = 9; csd[6] = 0x03; csd[7] = (byte) 0xFF; csd[8] = (byte) 0xC0; csd[9] = 0x03; csd[10] = (byte) 0x80;
                    }
                    out.add(0x00); out.add(0xFF);
                    pushBlock(csd, 0, 16);
                }
                case 10 -> {
                    byte[] cid = {0x03, 'S', 'D', 'T', 'E', 'S', 'T', '1', 0x10, (byte) 0xDE, (byte) 0xAD, (byte) 0xBE, (byte) 0xEF, 0x01, 0x23, 0x01};
                    out.add(0x00); out.add(0xFF);
                    pushBlock(cid, 0, 16);
                }
                case 12 -> {
                    streaming = false;
                    out.clear();
                    for (int v : new int[] {0xFF, 0x00, 0x00, 0x00, 0xFF}) out.add(v);
                }
                case 13 -> { out.add(0x00); out.add(0x00); }
                case 16 -> out.add(idle);
                case 17 -> { out.add(0x00); out.add(0xFF); pushBlock(mem, (int) blockOf(arg) * 512, 512); }
                case 18 -> { out.add(0x00); streaming = true; streamBlock = blockOf(arg); }
                case 24, 25 -> { out.add(0x00); state = 1; wBlock = blockOf(arg); multiWrite = cmd == 25; }
                case 32, 33 -> out.add(0x00);
                case 38 -> { out.add(0x00); out.add(0x00); out.add(0x00); }
                case 55 -> { appNext = true; out.add(idle); }
                case 58 -> { out.add(idle); out.add(sdhc ? 0xC0 : 0x80); out.add(0xFF); out.add(0x80); out.add(0x00); }
                case 59 -> { crcOn = (arg & 1) != 0; out.add(idle); }
                default -> out.add(0x04);   // illegal command
            }
        }

        private int feed(int b) {
            if (state == 1) {
                if (b == 0xFE || b == 0xFC) { state = 2; wData.clear(); }
                else if (b == 0xFD) {
                    state = 0;
                    out.add(0xFF); out.add(0x00); out.add(0x00); out.add(0xFF);
                    return 0xFF;
                }
            } else if (state == 2) {
                wData.add(b);
                if (wData.size() == 514) {
                    byte[] d = new byte[512];
                    for (int i = 0; i < 512; i++) d[i] = (byte) (int) wData.get(i);
                    int got = (wData.get(512) << 8) | wData.get(513);
                    boolean ok = !crcOn || got == SdSpiMinimal.crc16(d, 0, 512);
                    if (ok) { System.arraycopy(d, 0, mem, (int) wBlock * 512, 512); wBlock++; }
                    out.add(ok ? 0xE5 : 0xEB); out.add(0x00); out.add(0x00); out.add(0xFF);
                    state = multiWrite ? 1 : 0;
                    return 0xFF;   // the data response follows on the NEXT byte clock
                }
            } else if (!cmdBuf.isEmpty() || (b & 0xC0) == 0x40) {
                cmdBuf.add(b);
                if (cmdBuf.size() == 6) { doCommand(); cmdBuf.clear(); }
                return 0xFF;
            }
            if (!out.isEmpty()) return out.poll();
            if (streaming && state == 0) {
                pushBlock(mem, (int) streamBlock * 512, 512);
                streamBlock++;
                return out.poll();
            }
            return 0xFF;
        }

        @Override public void idleClocks(int n) { if (!selected && n >= 80) gotIdleClocks = true; }
        @Override public void setSpeed(int hz) { speed = hz; }
        @Override public void select() { selected = true; }
        @Override public void deselect() { selected = false; if (state == 0 && !cmdBuf.isEmpty()) csTogglesInCommand++; }
        @Override public void readInto(byte[] buf) { for (int i = 0; i < buf.length; i++) buf[i] = (byte) feed(0xFF); }
        @Override public void write(byte[] data) { for (byte x : data) feed(x & 0xFF); }
        @Override public byte[] read(int n) { byte[] b = new byte[n]; readInto(b); return b; }
        @Override public byte[] writeRead(byte[] data, int n) { write(data); return read(n); }
        @Override public void enable() {}
        @Override public void disable() {}
        @Override public boolean isEnabled() { return true; }
        @Override public InputPin intPin() { return null; }
        @Override public OutputPin enPin() { return null; }
        @Override public void close() {}
    }

    @Test
    void crcHelpers() {
        assertEquals(0x31C3, SdSpiMinimal.crc16("123456789".getBytes(), 0, 9));
        assertEquals(0x95, (SdSpiMinimal.crc7(new byte[] {0x40, 0, 0, 0, 0}, 5) << 1) | 1);
    }

    @Test
    void initV2Sdhc() throws IOException {
        SdModel m = new SdModel(true, true);
        SdSpiMinimal sd = new SdSpiMinimal(m);
        assertEquals(CardType.SDHC, sd.cardType());
        assertEquals(8192, sd.blockCount());
        assertTrue(m.gotIdleClocks, "80 idle clocks before CMD0");
        assertEquals(12_000_000, m.speed);
        assertEquals(0, m.csTogglesInCommand);
    }

    @Test
    void initV1ByteAddressed() throws IOException {
        SdModel m = new SdModel(false, false);
        SdSpiMinimal sd = new SdSpiMinimal(m);
        assertEquals(CardType.SD_V1, sd.cardType());
        assertEquals(2_097_152L, sd.blockCount());
        assertTrue(m.cmdLog.contains(16), "CMD16 for a byte-addressed card");
    }

    @Test
    void noCardIsNotReady() {
        SdModel dead = new SdModel(true, true) {
            @Override public void readInto(byte[] buf) { Arrays.fill(buf, (byte) 0xFF); }
        };
        BlockDeviceException e = assertThrows(BlockDeviceException.class, () -> new SdSpiMinimal(dead));
        assertEquals(Kind.NOT_READY, e.kind());
    }

    @Test
    void minimalBlockRoundtrip() throws IOException {
        SdSpiMinimal sd = new SdSpiMinimal(new SdModel(true, true));
        byte[] w = new byte[512];
        for (int i = 0; i < 512; i++) w[i] = (byte) (i * 7 + 3);
        sd.writeBlock(5, w);
        assertArrayEquals(w, sd.readBlock(5));
        assertEquals(Kind.OUT_OF_RANGE, assertThrows(BlockDeviceException.class, () -> sd.readBlock(8192)).kind());
    }

    @Test
    void fullMultiBlockAndInfo() throws IOException {
        SdSpiFull sd = new SdSpiFull(new SdModel(true, true));
        assertEquals(512, sd.blockSize());
        assertFalse(sd.needsErase());
        assertEquals("TEST1", sd.info().name());
        byte[] w = new byte[4 * 512];
        for (int k = 0; k < w.length; k++) w[k] = (byte) (k ^ (k >> 8));
        sd.write(100, w);
        sd.sync();
        assertArrayEquals(w, sd.read(100, 4));
        sd.erase(100, 4);
        assertEquals(Kind.OUT_OF_RANGE, assertThrows(BlockDeviceException.class, () -> sd.write(8190, w)).kind());
        assertTrue((sd.ocr() & 0x40000000) != 0);
        assertTrue(sd.isPresent());
    }

    @Test
    void crcErrorIsReported() throws IOException {
        SdModel m = new SdModel(true, true);
        SdSpiFull sd = new SdSpiFull(m);
        m.corruptReadCrc = true;
        assertEquals(Kind.CRC, assertThrows(BlockDeviceException.class, () -> sd.readBlock(1)).kind());
        sd.setCrc(false);
        assertEquals(512, sd.readBlock(1).length);
    }

    @Test
    void blockDeviceContractSharedWithRam() throws IOException {
        for (BlockDevice dev : new BlockDevice[] {new SdSpiFull(new SdModel(true, true)), new RamBlockDevice(512, 64, 1, false)}) {
            byte[] w = new byte[512];
            Arrays.fill(w, (byte) 0xA5);
            dev.write(3, w);
            assertArrayEquals(w, dev.read(3, 1));
            assertEquals(Kind.OUT_OF_RANGE, assertThrows(BlockDeviceException.class, () -> dev.read(dev.blockCount(), 1)).kind());
        }
    }

    @Test
    void ramNorSemantics() throws IOException {
        RamBlockDevice nor = new RamBlockDevice(512, 16, 8, false);
        assertEquals((byte) 0xFF, nor.read(0, 1)[0]);
        nor.write(0, new byte[512]);
        assertEquals(Kind.NOT_ERASED, assertThrows(BlockDeviceException.class, () -> nor.write(0, new byte[512])).kind());
        nor.erase(0, 8);
        nor.write(0, new byte[512]);
        assertEquals(Kind.OUT_OF_RANGE, assertThrows(BlockDeviceException.class, () -> nor.erase(1, 8)).kind());
        RamBlockDevice auto = new RamBlockDevice(512, 16, 8, true);
        auto.write(2, new byte[512]);
        auto.write(2, new byte[512]);
    }
}
