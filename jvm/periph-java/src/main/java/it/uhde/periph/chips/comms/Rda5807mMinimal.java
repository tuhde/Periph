package it.uhde.periph.chips.comms;

import it.uhde.periph.transport.Transport;

import java.io.IOException;

/**
 * RDA5807M — single-chip FM stereo radio tuner with I²C interface (minimal driver).
 *
 * <p>Tunes to a station, adjusts volume, mutes, and seeks the next station. No
 * configuration required beyond the transport.
 *
 * <p>Unlike most chips in this project, the RDA5807M has no register-pointer
 * byte: writes always start at the fixed register 0x02 and reads always start
 * at the fixed register 0x0A. This driver keeps an in-memory shadow of
 * registers 0x02-0x07 (6 big-endian 16-bit words) and rewrites all of them on
 * every change, since the chip cannot be told to start a write anywhere else.
 *
 * <p>Fixed I²C address: 0x10.
 */
public class Rda5807mMinimal {

    public static final int BAND_US_EUROPE = 0;
    public static final int BAND_JAPAN = 1;
    public static final int BAND_WORLD = 2;
    public static final int BAND_EAST_EUROPE = 3;

    public static final int SPACE_100K = 0;
    public static final int SPACE_200K = 1;
    public static final int SPACE_50K = 2;
    public static final int SPACE_25K = 3;

    private static final int[] BAND_BASE_KHZ = {87000, 76000, 76000, 65000};
    private static final int[] SPACE_KHZ = {100, 200, 50, 25};

    private static final int STC_TIMEOUT_MS = 500;
    private static final int STC_POLL_MS = 1;

    protected static final int DHIZ = 0x8000;
    protected static final int DMUTE = 0x4000;
    protected static final int MONO = 0x2000;
    protected static final int BASS = 0x1000;
    protected static final int SEEKUP = 0x0200;
    protected static final int SEEK = 0x0100;
    protected static final int SKMODE = 0x0080;
    protected static final int RDS_EN = 0x0008;
    protected static final int NEW_METHOD = 0x0004;
    protected static final int SOFT_RESET = 0x0002;
    protected static final int ENABLE = 0x0001;

    protected static final int TUNE = 0x0010;

    protected static final int DE = 0x0800;
    protected static final int SOFTMUTE_EN = 0x0200;
    protected static final int AFCD = 0x0100;

    protected static final int INT_MODE = 0x8000;

    protected static final int BAND_65M_50M = 0x0200;

    protected static final int RDSR = 0x8000;
    protected static final int STC = 0x4000;
    protected static final int SF = 0x2000;
    protected static final int ST = 0x0400;

    protected static final int FM_TRUE = 0x0100;
    protected static final int FM_READY = 0x0080;

    protected final Transport transport;
    protected final int[] regs = new int[6];
    protected int band;
    protected int space;
    protected boolean eastEurope50m;

    /**
     * Construct the driver and tune to the initial frequency.
     *
     * @param transport     I²C transport bound to address 0x10
     * @param frequencyMhz  initial frequency in MHz
     * @param volume        initial volume, 0 (mute) to 15 (max)
     * @throws IOException on I²C error
     */
    public Rda5807mMinimal(Transport transport, double frequencyMhz, int volume) throws IOException {
        this.transport = transport;
        this.band = BAND_WORLD;
        this.space = SPACE_100K;
        this.eastEurope50m = false;

        int ctrl = DHIZ | DMUTE | SKMODE | NEW_METHOD | ENABLE;
        int chan = freqToChan(band, space, eastEurope50m, frequencyMhz);
        int chanReg = (chan << 6) | TUNE | (band << 2) | space;
        int r4 = SOFTMUTE_EN | DE;
        int r5 = INT_MODE | (8 << 8) | (volume & 0x0F);
        int r6 = 0x0000;
        int r7 = (16 << 10) | BAND_65M_50M | 0x0002;

        regs[0] = ctrl;
        regs[1] = chanReg;
        regs[2] = r4;
        regs[3] = r5;
        regs[4] = r6;
        regs[5] = r7;

        writeRegs();
        waitStc();
        regs[1] &= ~TUNE;
    }

    /**
     * Construct the driver with default parameters (100.0 MHz, volume 8).
     *
     * @param transport I²C transport bound to address 0x10
     * @throws IOException on I²C error
     */
    public Rda5807mMinimal(Transport transport) throws IOException {
        this(transport, 100.0, 8);
    }

    private static int freqToChan(int band, int space, boolean eastEurope50m, double frequencyMhz) {
        int base = (band == 3 && eastEurope50m) ? 50000 : BAND_BASE_KHZ[band];
        int freqKhz = (int) Math.round(frequencyMhz * 1000.0);
        int chan = (int) Math.round((freqKhz - base) / (double) SPACE_KHZ[space]);
        if (chan < 0) chan = 0;
        if (chan > 1023) chan = 1023;
        return chan;
    }

    private static double chanToFreq(int band, int space, boolean eastEurope50m, int chan) {
        int base = (band == 3 && eastEurope50m) ? 50000 : BAND_BASE_KHZ[band];
        return (base + chan * (double) SPACE_KHZ[space]) / 1000.0;
    }

    protected void writeRegs() throws IOException {
        byte[] buf = new byte[12];
        for (int i = 0; i < 6; i++) {
            buf[i * 2] = (byte) (regs[i] >> 8);
            buf[i * 2 + 1] = (byte) (regs[i] & 0xFF);
        }
        transport.write(buf);
    }

    protected int[] readStatus(int n) throws IOException {
        byte[] buf = transport.read(n);
        int[] words = new int[n / 2];
        for (int i = 0; i < words.length; i++) {
            words[i] = ((buf[i * 2] & 0xFF) << 8) | (buf[i * 2 + 1] & 0xFF);
        }
        return words;
    }

    protected int waitStc() throws IOException {
        int elapsed = 0;
        while (elapsed < STC_TIMEOUT_MS) {
            int statusA = readStatus(2)[0];
            if ((statusA & STC) != 0) return statusA;
            try {
                Thread.sleep(STC_POLL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            elapsed += STC_POLL_MS;
        }
        return 0;
    }

    /**
     * Tune to a frequency, blocking until the tune completes.
     *
     * @param frequencyMhz target frequency in MHz
     * @throws IOException on I²C error
     */
    public void setFrequency(double frequencyMhz) throws IOException {
        int chan = freqToChan(band, space, eastEurope50m, frequencyMhz);
        regs[1] = (chan << 6) | TUNE | (band << 2) | space;
        writeRegs();
        waitStc();
        regs[1] &= ~TUNE;
    }

    /**
     * Read the currently tuned frequency.
     *
     * @return frequency in MHz, derived from READCHAN
     * @throws IOException on I²C error
     */
    public double frequency() throws IOException {
        int statusA = readStatus(2)[0];
        int readchan = statusA & 0x03FF;
        return chanToFreq(band, space, eastEurope50m, readchan);
    }

    /**
     * Set the output volume.
     *
     * @param level volume 0 (mute) to 15 (max), logarithmic scale
     * @throws IOException on I²C error
     */
    public void setVolume(int level) throws IOException {
        regs[3] = (regs[3] & ~0x000F) | (level & 0x0F);
        writeRegs();
    }

    /**
     * Mute or unmute the audio output.
     *
     * @param enable true to mute, false for normal operation
     * @throws IOException on I²C error
     */
    public void mute(boolean enable) throws IOException {
        if (enable) regs[0] &= ~DMUTE;
        else regs[0] |= DMUTE;
        writeRegs();
    }

    /**
     * Seek to the next station, blocking until the seek completes.
     *
     * @param up true to seek upward, false to seek downward
     * @return the new frequency in MHz, or {@code null} if the seek failed (SF flag set)
     * @throws IOException on I²C error
     */
    public Double seek(boolean up) throws IOException {
        if (up) regs[0] |= SEEKUP;
        else regs[0] &= ~SEEKUP;
        regs[0] |= SEEK;
        writeRegs();
        int statusA = waitStc();
        regs[0] &= ~SEEK;
        writeRegs();

        if ((statusA & SF) != 0) return null;
        int readchan = statusA & 0x03FF;
        return chanToFreq(band, space, eastEurope50m, readchan);
    }
}
