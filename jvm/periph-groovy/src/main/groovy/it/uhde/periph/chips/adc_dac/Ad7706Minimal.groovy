package it.uhde.periph.chips.adc_dac

import groovy.transform.CompileStatic
import it.uhde.periph.connection.Connection

/**
 * AD7706 — 3-channel, 16-bit sigma-delta ADC with SPI interface (minimal driver).
 *
 * Reads calibrated voltage from Channel 1 (AIN1 relative to COMMON) with
 * sensible defaults. The driver uses the chip's two-phase register-access
 * protocol and polls the 0/DRDY bit of the Communication Register over SPI
 * rather than requiring a dedicated DRDY GPIO.
 *
 * Sensible defaults baked in at construction: Channel 1 selected, gain 1,
 * bipolar, unbuffered analog input, 50 Hz output rate on a 2.4576/4.9152 MHz
 * clock (or 20 Hz on 1/2 MHz), with Channel 1 self-calibrated once.
 */
@CompileStatic
class Ad7706Minimal {

    static final int MCLK_1MHZ      = 1000000
    static final int MCLK_2MHZ      = 2000000
    static final int MCLK_2_4576MHZ = 2457600
    static final int MCLK_4_9152MHZ = 4915200

    protected static final int REG_COMM   = 0x00
    protected static final int REG_SETUP  = 0x10
    protected static final int REG_CLOCK  = 0x20
    protected static final int REG_DATA   = 0x30
    protected static final int REG_OFFSET = 0x60
    protected static final int REG_GAIN   = 0x70

    protected static final int RW_WRITE = 0x00
    protected static final int RW_READ  = 0x08

    protected static final int CH1 = 0x00
    protected static final int CH2 = 0x01
    /** AD7706 channel 3 — AIN3 measured against COMMON. */
    protected static final int CH3 = 0x03

    protected static final int MODE_NORMAL   = 0x00
    protected static final int MODE_SELF_CAL = 0x40
    protected static final int MODE_ZERO_SYS = 0x80
    protected static final int MODE_FULL_SYS = 0xC0

    protected static final int[] GAIN_BITS = [0x00, 0x08, 0x10, 0x18, 0x20, 0x28, 0x30, 0x38]

    protected static final int BIPOLAR    = 0x00
    protected static final int UNIPOLAR   = 0x04
    protected static final int UNBUFFERED = 0x00
    protected static final int BUFFERED   = 0x02
    protected static final int FSYNC_RUN  = 0x00
    protected static final int STBY_RUN   = 0x00
    protected static final int STBY_SLEEP = 0x04
    protected static final int DRDY_MASK  = 0x80

    protected static final int[] FS_RATES_1MHZ   = [20, 25, 100, 200]
    protected static final int[] FS_RATES_2_4MHZ = [50, 60, 250, 500]

    protected final Connection connection
    protected final float vref
    protected final int mclkHz
    protected int gain = 1
    protected boolean bipolar = true
    protected boolean buffered = false

    /**
     * Construct and initialise the AD7706.
     * @param connection SPI connection bound to the device.
     * @param vref reference voltage in V.
     * @param mclkHz master clock frequency in Hz.
     */
    Ad7706Minimal(Connection connection, float vref, int mclkHz) {
        if (mclkHz != MCLK_1MHZ && mclkHz != MCLK_2MHZ && mclkHz != MCLK_2_4576MHZ && mclkHz != MCLK_4_9152MHZ) {
            throw new IllegalArgumentException("mclkHz must be 1000000, 2000000, 2457600, or 4915200")
        }
        this.connection = connection
        this.vref = vref
        this.mclkHz = mclkHz

        int defaultRate = (mclkHz >= MCLK_2_4576MHZ) ? FS_RATES_2_4MHZ[0] : FS_RATES_1MHZ[0]
        configureClock(defaultRate)

        int setup = MODE_SELF_CAL | GAIN_BITS[0] | BIPOLAR | UNBUFFERED | FSYNC_RUN
        writeRegChannel(REG_SETUP, setup, CH1, 1)
        waitDrdy()
    }

    /** @return externally-supplied reference voltage in V. */
    float getVref() { return vref }

    /** @return master clock frequency in Hz. */
    int getMclkHz() { return mclkHz }

    /** @return currently-configured PGA gain. */
    int getGain() { return gain }

    /** @return true if bipolar mode (offset binary), false if unipolar. */
    boolean isBipolar() { return bipolar }

    /** @return true if the analog input buffer is enabled. */
    boolean isBuffered() { return buffered }

    protected static int commByte(int reg, boolean read, int channel) {
        return (reg | (read ? RW_READ : RW_WRITE) | (channel & 0x03)) & 0xFF
    }

    protected void waitDrdy() {
        while (true) {
            byte[] buf = connection.writeRead(new byte[]{(byte) commByte(REG_COMM, true, CH1)}, 1)
            if ((buf[0] & DRDY_MASK) == 0) return
        }
    }

    protected void configureClock(int outputRateHz) {
        int clkBit = (mclkHz >= MCLK_2_4576MHZ) ? 0x04 : 0x00
        int clkdivBit = (mclkHz == MCLK_2MHZ || mclkHz == MCLK_4_9152MHZ) ? 0x08 : 0x00
        int[] rates = (mclkHz >= MCLK_2_4576MHZ) ? FS_RATES_2_4MHZ : FS_RATES_1MHZ
        int fsBits = 0
        for (int i = 0; i < 4; i++) {
            if (rates[i] == outputRateHz) { fsBits = i; break }
        }
        writeRegChannel(REG_CLOCK, clkdivBit | clkBit | fsBits, CH1, 1)
    }

    protected void writeRegChannel(int reg, int value, int channel, int nBytes) {
        byte[] buf = new byte[1 + nBytes]
        buf[0] = (byte) commByte(reg, false, channel)
        for (int i = nBytes - 1; i >= 0; i--) {
            buf[1 + (nBytes - 1 - i)] = (byte) ((value >> (8 * i)) & 0xFF)
        }
        connection.write(buf)
    }

    protected int readRegChannel(int reg, int channel, int nBytes) {
        byte[] comm = new byte[]{(byte) commByte(reg, true, channel)}
        byte[] raw = connection.writeRead(comm, nBytes)
        int value = 0
        for (int i = 0; i < nBytes; i++) {
            value = (value << 8) | (raw[i] & 0xFF)
        }
        return value
    }

    protected float codeToVoltage(int code, int gainValue, boolean bipolarFlag) {
        if (bipolarFlag) {
            return ((float) (code - 32768)) / 32768.0f * (vref / (float) gainValue)
        }
        return ((float) code) / 65536.0f * (vref / (float) gainValue)
    }

    /** Block until DRDY, then read and return the raw 16-bit Data Register code on Channel 1. */
    int readRaw() {
        waitDrdy()
        return readRegChannel(REG_DATA, CH1, 2)
    }

    /** Block until DRDY, then return the input voltage on Channel 1 in V. */
    float readVoltage() {
        int code = readRaw()
        return codeToVoltage(code, gain, bipolar)
    }
}
