package it.uhde.periph.chips.adc_dac;

import it.uhde.periph.connection.Connection;

import java.io.IOException;

/**
 * AD7706 — 3-channel, 16-bit sigma-delta ADC with SPI interface (minimal driver).
 *
 * <p>Reads calibrated voltage from Channel 1 (AIN1 relative to COMMON) with
 * sensible defaults. The driver uses the chip's two-phase register-access
 * protocol (Communication Register write followed by the data transfer within
 * one CS assertion) and polls the {@code 0/DRDY} bit of the Communication
 * Register over SPI rather than requiring a dedicated DRDY GPIO.
 *
 * <p>Sensible defaults baked in at construction:
 * <ul>
 *   <li>Channel 1 (AIN1 vs COMMON) selected.</li>
 *   <li>Gain 1, bipolar, analog input buffer bypassed.</li>
 *   <li>50 Hz output rate on a 2.4576/4.9152 MHz clock, or 20 Hz on 1/2 MHz.</li>
 *   <li>Self-calibration of Channel 1 runs once at construction.</li>
 * </ul>
 *
 * <p>Full configuration control, per-channel calibration, and direct access
 * to the 24-bit calibration coefficients are in {@link Ad7706Full}.
 *
 * @param connection SPI connection bound to the device (Mode 3, ≤5 MHz).
 * @param vref       externally-supplied reference voltage in V (REF IN(+) − REF IN(−)).
 * @param mclkHz     master clock frequency in Hz; one of 1 000 000, 2 000 000,
 *                  2 457 600, or 4 915 200.
 */
public class Ad7706Minimal {

    /** Master clock frequency 1 MHz. */
    public static final int MCLK_1MHZ      = 1000000;
    /** Master clock frequency 2 MHz. */
    public static final int MCLK_2MHZ      = 2000000;
    /** Master clock frequency 2.4576 MHz. */
    public static final int MCLK_2_4576MHZ = 2457600;
    /** Master clock frequency 4.9152 MHz. */
    public static final int MCLK_4_9152MHZ = 4915200;

    protected static final int REG_COMM   = 0x00;
    protected static final int REG_SETUP  = 0x10;
    protected static final int REG_CLOCK  = 0x20;
    protected static final int REG_DATA   = 0x30;
    protected static final int REG_OFFSET = 0x60;
    protected static final int REG_GAIN   = 0x70;

    protected static final int RW_WRITE = 0x00;
    protected static final int RW_READ  = 0x08;

    protected static final int CH1 = 0x00;
    protected static final int CH2 = 0x01;
    /** AD7706 channel 3 — AIN3 measured against COMMON. */
    protected static final int CH3 = 0x03;

    protected static final int MODE_NORMAL   = 0x00;
    protected static final int MODE_SELF_CAL = 0x40;
    protected static final int MODE_ZERO_SYS = 0x80;
    protected static final int MODE_FULL_SYS = 0xC0;

    protected static final int[] GAIN_BITS = {0x00, 0x08, 0x10, 0x18, 0x20, 0x28, 0x30, 0x38};

    protected static final int BIPOLAR    = 0x00;
    protected static final int UNIPOLAR   = 0x04;
    protected static final int UNBUFFERED = 0x00;
    protected static final int BUFFERED   = 0x02;
    protected static final int FSYNC_RUN  = 0x00;
    protected static final int STBY_RUN   = 0x00;
    protected static final int STBY_SLEEP = 0x04;
    protected static final int DRDY_MASK  = 0x80;

    protected static final int[] FS_RATES_1MHZ   = {20, 25, 100, 200};
    protected static final int[] FS_RATES_2_4MHZ = {50, 60, 250, 500};

    protected final Connection connection;
    protected final float vref;
    protected final int mclkHz;
    protected int gain;
    protected boolean bipolar;
    protected boolean buffered;

    /**
     * Construct the driver and run the Initialization Sequence (Clock Register,
     * self-calibrate Channel 1).
     *
     * @param connection SPI connection bound to the device.
     * @param vref       reference voltage in V.
     * @param mclkHz     master clock frequency in Hz.
     * @throws IOException on SPI error or if {@code mclkHz} is not one of the four
     *                     supported frequencies.
     */
    public Ad7706Minimal(Connection connection, float vref, int mclkHz) throws IOException {
        if (mclkHz != MCLK_1MHZ && mclkHz != MCLK_2MHZ && mclkHz != MCLK_2_4576MHZ && mclkHz != MCLK_4_9152MHZ) {
            throw new IllegalArgumentException("mclkHz must be 1000000, 2000000, 2457600, or 4915200");
        }
        this.connection = connection;
        this.vref = vref;
        this.mclkHz = mclkHz;
        this.gain = 1;
        this.bipolar = true;
        this.buffered = false;

        int defaultRate = (mclkHz >= MCLK_2_4576MHZ) ? FS_RATES_2_4MHZ[0] : FS_RATES_1MHZ[0];
        configureClock(defaultRate);

        int setup = MODE_SELF_CAL | GAIN_BITS[0] | BIPOLAR | UNBUFFERED | FSYNC_RUN;
        writeRegChannel(REG_SETUP, setup, CH1, 1);
        waitDrdy();
    }

    /** @return externally-supplied reference voltage in V. */
    public float getVref() { return vref; }

    /** @return master clock frequency in Hz. */
    public int getMclkHz() { return mclkHz; }

    /** @return currently-configured PGA gain. */
    public int getGain() { return gain; }

    /** @return true if bipolar mode (offset binary), false if unipolar. */
    public boolean isBipolar() { return bipolar; }

    /** @return true if the analog input buffer is enabled. */
    public boolean isBuffered() { return buffered; }

    protected static int commByte(int reg, boolean read, int channel) {
        return (reg | (read ? RW_READ : RW_WRITE) | (channel & 0x03)) & 0xFF;
    }

    protected void waitDrdy() throws IOException {
        while (true) {
            byte[] buf = connection.writeRead(new byte[]{(byte) commByte(REG_COMM, true, CH1)}, 1);
            if ((buf[0] & DRDY_MASK) == 0) {
                return;
            }
        }
    }

    protected void configureClock(int outputRateHz) throws IOException {
        int clkBit = (mclkHz >= MCLK_2_4576MHZ) ? 0x04 : 0x00;
        int clkdivBit = (mclkHz == MCLK_2MHZ || mclkHz == MCLK_4_9152MHZ) ? 0x08 : 0x00;
        int[] rates = (mclkHz >= MCLK_2_4576MHZ) ? FS_RATES_2_4MHZ : FS_RATES_1MHZ;
        int fsBits = 0;
        for (int i = 0; i < 4; i++) {
            if (rates[i] == outputRateHz) { fsBits = i; break; }
        }
        writeRegChannel(REG_CLOCK, clkdivBit | clkBit | fsBits, CH1, 1);
    }

    protected void writeRegChannel(int reg, int value, int channel, int nBytes) throws IOException {
        byte[] buf = new byte[1 + nBytes];
        buf[0] = (byte) commByte(reg, false, channel);
        for (int i = nBytes - 1; i >= 0; i--) {
            buf[1 + (nBytes - 1 - i)] = (byte) ((value >> (8 * i)) & 0xFF);
        }
        connection.write(buf);
    }

    protected int readRegChannel(int reg, int channel, int nBytes) throws IOException {
        byte[] comm = new byte[]{(byte) commByte(reg, true, channel)};
        byte[] raw = connection.writeRead(comm, nBytes);
        int value = 0;
        for (int i = 0; i < nBytes; i++) {
            value = (value << 8) | (raw[i] & 0xFF);
        }
        return value;
    }

    protected float codeToVoltage(int code, int gainValue, boolean bipolarFlag) {
        if (bipolarFlag) {
            return (((float) (code - 32768)) / 32768.0f) * (vref / (float) gainValue);
        }
        return ((float) code / 65536.0f) * (vref / (float) gainValue);
    }

    /**
     * Block until DRDY, then read and return the raw 16-bit Data Register code on Channel 1.
     *
     * @return raw 16-bit code (0–65535).
     * @throws IOException on SPI error.
     */
    public int readRaw() throws IOException {
        waitDrdy();
        return readRegChannel(REG_DATA, CH1, 2);
    }

    /**
     * Block until DRDY, then return the input voltage on Channel 1 in V.
     *
     * @return voltage in V.
     * @throws IOException on SPI error.
     */
    public float readVoltage() throws IOException {
        int code = readRaw();
        return codeToVoltage(code, gain, bipolar);
    }
}
