package it.uhde.periph.chips.comms;

import it.uhde.periph.connection.MockConnection;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Rda5807mTest {

    // Register bit constants and frequency<->channel math, mirrored from
    // RDA5807MMinimal/RDA5807MFull (protected there, so re-declared here to
    // build expected values). BAND_*/SPACE_* are public on the driver
    // itself so those are referenced directly.
    private static final int[] BAND_BASE_KHZ = {87000, 76000, 76000, 65000};
    private static final int[] SPACE_KHZ = {100, 200, 50, 25};

    private static final int DHIZ = 0x8000;
    private static final int DMUTE = 0x4000;
    private static final int MONO = 0x2000;
    private static final int BASS = 0x1000;
    private static final int SEEKUP = 0x0200;
    private static final int SEEK = 0x0100;
    private static final int SKMODE = 0x0080;
    private static final int RDS_EN = 0x0008;
    private static final int NEW_METHOD = 0x0004;
    private static final int SOFT_RESET = 0x0002;
    private static final int ENABLE = 0x0001;
    private static final int TUNE = 0x0010;
    private static final int DE = 0x0800;
    private static final int SOFTMUTE_EN = 0x0200;
    private static final int AFCD = 0x0100;
    private static final int INT_MODE = 0x8000;
    private static final int BAND_65M_50M = 0x0200;
    private static final int RDSR = 0x8000;
    private static final int STC = 0x4000;
    private static final int SF = 0x2000;
    private static final int ST = 0x0400;
    private static final int FM_TRUE = 0x0100;
    private static final int FM_READY = 0x0080;

    private static int freqToChan(int band, int space, boolean east50, double freqMhz) {
        int base = (band == 3 && east50) ? 50000 : BAND_BASE_KHZ[band];
        int freqKhz = (int) Math.round(freqMhz * 1000.0);
        int chan = (int) Math.round((freqKhz - base) / (double) SPACE_KHZ[space]);
        return Math.max(0, Math.min(1023, chan));
    }

    private static double chanToFreq(int band, int space, boolean east50, int chan) {
        int base = (band == 3 && east50) ? 50000 : BAND_BASE_KHZ[band];
        return (base + chan * (double) SPACE_KHZ[space]) / 1000.0;
    }

    private static byte[] regsBytes(int[] regs) {
        byte[] buf = new byte[12];
        for (int i = 0; i < 6; i++) {
            buf[i * 2] = (byte) (regs[i] >> 8);
            buf[i * 2 + 1] = (byte) (regs[i] & 0xFF);
        }
        return buf;
    }

    private static byte[] statusBytes(int... words) {
        byte[] buf = new byte[words.length * 2];
        for (int i = 0; i < words.length; i++) {
            buf[i * 2] = (byte) (words[i] >> 8);
            buf[i * 2 + 1] = (byte) (words[i] & 0xFF);
        }
        return buf;
    }

    /** Bundle returned by {@link #newSensor}: the mock, the driver under
     * test, and the expected post-init shadow register array (TUNE already
     * cleared, mirroring what the driver does once it observes STC). */
    private static final class Fixture {
        MockConnection connection;
        RDA5807MFull sensor;
        int[] regs;
        int band, space;
        boolean east50;
    }

    /** Construct a fresh RDA5807MFull with a queued STC-set status so the
     * blocking waitStc() inside the constructor resolves on its first poll. */
    private static Fixture newSensor(double frequencyMhz, int volume) throws Exception {
        Fixture f = new Fixture();
        f.connection = new MockConnection();
        f.connection.queueRead(statusBytes(STC));
        f.band = RDA5807MMinimal.BAND_WORLD;
        f.space = RDA5807MMinimal.SPACE_100K;
        f.east50 = false;
        int chan0 = freqToChan(f.band, f.space, f.east50, frequencyMhz);
        f.regs = new int[]{
                DHIZ | DMUTE | SKMODE | NEW_METHOD | ENABLE,
                (chan0 << 6) | TUNE | (f.band << 2) | f.space,
                SOFTMUTE_EN | DE,
                INT_MODE | (8 << 8) | (volume & 0x0F),
                0x0000,
                (16 << 10) | BAND_65M_50M | 0x0002,
        };
        f.sensor = new RDA5807MFull(f.connection, frequencyMhz, volume);
        f.regs[1] &= ~TUNE;
        return f;
    }

    private static byte[] lastWrite(MockConnection connection) {
        var writes = connection.writes();
        return writes.get(writes.size() - 1);
    }

    @Test
    void initWritesRegs() throws Exception {
        Fixture f = newSensor(100.0, 8);
        int[] expected = f.regs.clone();
        expected[1] |= TUNE; // write happened before the shadow TUNE bit was cleared
        assertArrayEquals(regsBytes(expected), f.connection.writes().get(0));
    }

    @Test
    void frequency() throws Exception {
        Fixture f = newSensor(100.0, 8);
        f.connection.queueRead(statusBytes(250));
        double freq = f.sensor.frequency();
        assertEquals(chanToFreq(f.band, f.space, f.east50, 250), freq);
    }

    @Test
    void setFrequencyWrites() throws Exception {
        Fixture f = newSensor(100.0, 8);
        f.connection.queueRead(statusBytes(STC));
        f.sensor.setFrequency(103.5);
        int chan1 = freqToChan(f.band, f.space, f.east50, 103.5);
        f.regs[1] = (chan1 << 6) | TUNE | (f.band << 2) | f.space;
        assertArrayEquals(regsBytes(f.regs), lastWrite(f.connection));
    }

    @Test
    void setVolume() throws Exception {
        Fixture f = newSensor(100.0, 8);
        f.sensor.setVolume(5);
        f.regs[3] = (f.regs[3] & ~0x000F) | (5 & 0x0F);
        assertArrayEquals(regsBytes(f.regs), lastWrite(f.connection));
    }

    @Test
    void mute() throws Exception {
        Fixture f = newSensor(100.0, 8);
        f.sensor.mute(true);
        f.regs[0] &= ~DMUTE;
        assertArrayEquals(regsBytes(f.regs), lastWrite(f.connection));
        f.sensor.mute(false);
        f.regs[0] |= DMUTE;
        assertArrayEquals(regsBytes(f.regs), lastWrite(f.connection));
    }

    @Test
    void seekUpFound() throws Exception {
        Fixture f = newSensor(100.0, 8);
        f.connection.queueRead(statusBytes(STC | 300));
        Double result = f.sensor.seek(true);
        f.regs[0] |= SEEKUP;
        f.regs[0] |= SEEK;
        byte[] firstWrite = regsBytes(f.regs);
        f.regs[0] &= ~SEEK;
        byte[] secondWrite = regsBytes(f.regs);
        var writes = f.connection.writes();
        assertArrayEquals(firstWrite, writes.get(writes.size() - 2));
        assertArrayEquals(secondWrite, writes.get(writes.size() - 1));
        assertEquals(chanToFreq(f.band, f.space, f.east50, 300), result);
    }

    @Test
    void seekFails() throws Exception {
        Fixture f = newSensor(100.0, 8);
        f.connection.queueRead(statusBytes(STC | SF));
        Double result = f.sensor.seek(false);
        assertNull(result);
    }

    @Test
    void configureRetunes() throws Exception {
        Fixture f = newSensor(100.0, 8);
        f.connection.queueRead(statusBytes(500)); // configure() reads current frequency() first
        double currentFreq = chanToFreq(f.band, f.space, f.east50, 500);
        f.connection.queueRead(statusBytes(STC)); // for the resulting retune's waitStc

        f.sensor.configure(RDA5807MMinimal.BAND_US_EUROPE, RDA5807MMinimal.SPACE_50K, false,
                10, false, 3, true, null);

        f.band = RDA5807MMinimal.BAND_US_EUROPE;
        f.space = RDA5807MMinimal.SPACE_50K;
        f.regs[2] &= ~DE;
        f.regs[2] |= AFCD;
        f.regs[3] = (f.regs[3] & ~0x0F00) | ((10 & 0x0F) << 8);
        f.regs[0] &= ~SKMODE;
        f.regs[0] = (f.regs[0] & ~0x0070) | ((3 & 0x07) << 4);
        int chan2 = freqToChan(f.band, f.space, f.east50, currentFreq);
        f.regs[1] = (chan2 << 6) | TUNE | (f.band << 2) | f.space;
        assertArrayEquals(regsBytes(f.regs), lastWrite(f.connection));
    }

    @Test
    void configureNoRetune() throws Exception {
        Fixture f = newSensor(100.0, 8);
        f.connection.queueRead(statusBytes(0)); // configure() still reads frequency() first
        f.sensor.configure(null, null, null, 4, null, null, null, null);
        f.regs[3] = (f.regs[3] & ~0x0F00) | ((4 & 0x0F) << 8);
        assertArrayEquals(regsBytes(f.regs), lastWrite(f.connection));
    }

    @Test
    void bassMonoSoftmuteRds() throws Exception {
        Fixture f = newSensor(100.0, 8);

        f.sensor.setBassBoost(true);
        f.regs[0] |= BASS;
        assertArrayEquals(regsBytes(f.regs), lastWrite(f.connection));

        f.sensor.setMono(true);
        f.regs[0] |= MONO;
        assertArrayEquals(regsBytes(f.regs), lastWrite(f.connection));

        f.sensor.setSoftmute(false);
        f.regs[2] &= ~SOFTMUTE_EN;
        assertArrayEquals(regsBytes(f.regs), lastWrite(f.connection));

        f.sensor.enableRds(true);
        f.regs[0] |= RDS_EN;
        assertArrayEquals(regsBytes(f.regs), lastWrite(f.connection));
    }

    @Test
    void rdsReadyAndGroup() throws Exception {
        Fixture f = newSensor(100.0, 8);
        f.connection.queueRead(statusBytes(RDSR));
        assertTrue(f.sensor.rdsReady());
        f.connection.queueRead(statusBytes(0));
        assertTrue(!f.sensor.rdsReady());

        f.connection.queueRead(statusBytes(RDSR, 0, 0x1122, 0x3344, 0x5566, 0x7788));
        int[] group = f.sensor.readRdsGroup();
        assertArrayEquals(new int[]{0x1122, 0x3344, 0x5566, 0x7788}, group);
        f.connection.queueRead(statusBytes(0, 0, 0, 0, 0, 0));
        assertEquals(null, f.sensor.readRdsGroup());
    }

    @Test
    void statusFlags() throws Exception {
        Fixture f = newSensor(100.0, 8);
        f.connection.queueRead(statusBytes(ST));
        assertTrue(f.sensor.isStereo());
        f.connection.queueRead(statusBytes(0, FM_TRUE));
        assertTrue(f.sensor.isStation());
        f.connection.queueRead(statusBytes(0, FM_READY));
        assertTrue(f.sensor.isReady());
        f.connection.queueRead(statusBytes(0, (100 << 9) & 0xFFFF));
        assertEquals(100, f.sensor.signalStrength());
    }

    @Test
    void standby() throws Exception {
        Fixture f = newSensor(100.0, 8);
        f.sensor.standby(true);
        f.regs[0] &= ~ENABLE;
        assertArrayEquals(regsBytes(f.regs), lastWrite(f.connection));

        f.connection.queueRead(statusBytes(STC)); // standby(false)'s internal setFrequency's waitStc
        f.sensor.standby(false);
        f.regs[0] |= ENABLE;
        byte[] enableWrite = regsBytes(f.regs);
        int chan3 = freqToChan(f.band, f.space, f.east50, 100.0); // newSensor()'s default frequency, unchanged so far
        f.regs[1] = (chan3 << 6) | TUNE | (f.band << 2) | f.space;
        byte[] retuneWrite = regsBytes(f.regs);
        var writes = f.connection.writes();
        assertArrayEquals(enableWrite, writes.get(writes.size() - 2));
        assertArrayEquals(retuneWrite, writes.get(writes.size() - 1));
    }

    @Test
    void softReset() throws Exception {
        Fixture f = newSensor(100.0, 8);
        f.connection.queueRead(statusBytes(STC)); // softReset()'s internal setFrequency's waitStc
        f.sensor.softReset();
        f.regs[0] |= SOFT_RESET;
        byte[] setWrite = regsBytes(f.regs);
        f.regs[0] &= ~SOFT_RESET;
        byte[] clearWrite = regsBytes(f.regs);
        int chan4 = freqToChan(f.band, f.space, f.east50, 100.0); // newSensor()'s default frequency, unchanged so far
        f.regs[1] = (chan4 << 6) | TUNE | (f.band << 2) | f.space;
        byte[] retuneWrite = regsBytes(f.regs);
        var writes = f.connection.writes();
        assertArrayEquals(setWrite, writes.get(writes.size() - 3));
        assertArrayEquals(clearWrite, writes.get(writes.size() - 2));
        assertArrayEquals(retuneWrite, writes.get(writes.size() - 1));
    }
}
