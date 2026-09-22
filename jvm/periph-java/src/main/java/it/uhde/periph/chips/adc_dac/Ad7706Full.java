package it.uhde.periph.chips.adc_dac;

import it.uhde.periph.connection.Connection;

import java.io.IOException;

/**
 * AD7706 — 3-channel, 16-bit sigma-delta ADC with SPI interface (full driver).
 *
 * <p>Extends {@link Ad7706Minimal} with per-channel configuration (Channel 1
 * = AIN1/COMMON, Channel 2 = AIN2/COMMON, Channel 3 = AIN3/COMMON), all
 * eight PGA gains, unipolar/bipolar selection, buffered/unbuffered analog
 * inputs, all four output rates within the {@code mclkHz} family, self-
 * and system-calibration, direct access to the 24-bit calibration
 * coefficients, standby/wakeup power control, and an optional hardware
 * reset pin.
 */
public class Ad7706Full extends Ad7706Minimal {

    /** PGA gain settings exposed by {@link #configure}. */
    public static final int GAIN_1   = 0;
    /** PGA gain settings exposed by {@link #configure}. */
    public static final int GAIN_2   = 1;
    /** PGA gain settings exposed by {@link #configure}. */
    public static final int GAIN_4   = 2;
    /** PGA gain settings exposed by {@link #configure}. */
    public static final int GAIN_8   = 3;
    /** PGA gain settings exposed by {@link #configure}. */
    public static final int GAIN_16  = 4;
    /** PGA gain settings exposed by {@link #configure}. */
    public static final int GAIN_32  = 5;
    /** PGA gain settings exposed by {@link #configure}. */
    public static final int GAIN_64  = 6;
    /** PGA gain settings exposed by {@link #configure}. */
    public static final int GAIN_128 = 7;

    private final OutputPin resetPin;

    /**
     * Construct and initialise the AD7706.
     *
     * @param connection SPI connection bound to the device.
     * @param vref       reference voltage in V.
     * @param mclkHz     master clock frequency in Hz.
     * @param resetPin   Optional hardware RESET pin; may be {@code null}.
     * @throws IOException on SPI error.
     */
    public Ad7706Full(Connection connection, float vref, int mclkHz, OutputPin resetPin) throws IOException {
        super(connection, vref, mclkHz);
        this.resetPin = resetPin;
    }

    /**
     * Construct and initialise the AD7706 without a hardware reset pin.
     *
     * @param connection SPI connection bound to the device.
     * @param vref       reference voltage in V.
     * @param mclkHz     master clock frequency in Hz.
     * @throws IOException on SPI error.
     */
    public Ad7706Full(Connection connection, float vref, int mclkHz) throws IOException {
        this(connection, vref, mclkHz, null);
    }

    /**
     * Write the Setup and Clock Registers for the given channel.
     *
     * <p>Does not calibrate — call {@link #selfCalibrate} (or one of the
     * system-calibration methods) afterward. The first {@code readRaw}/{@code readVoltage}
     * after a configuration change may be stale; recalibration is recommended
     * whenever gain, filter notch, or bipolar/unipolar mode changes.
     *
     * @param channel       1, 2, or 3 (AIN1, AIN2, AIN3 — all relative to COMMON).
     * @param gain          PGA gain: 1, 2, 4, 8, 16, 32, 64, or 128.
     * @param bipolar       {@code true} for bipolar, {@code false} for unipolar.
     * @param buffered      {@code true} to enable the analog input buffer.
     * @param outputRateHz  one of the four rates in the {@code mclkHz} family.
     * @throws IOException on SPI error.
     */
    public void configure(int channel, int gain, boolean bipolar, boolean buffered, int outputRateHz) throws IOException {
        int ch;
        switch (channel) {
            case 1: ch = CH1; break;
            case 2: ch = CH2; break;
            case 3: ch = CH3; break;
            default: throw new IllegalArgumentException("channel must be 1, 2, or 3");
        }
        if (gain != 1 && gain != 2 && gain != 4 && gain != 8 && gain != 16 && gain != 32 && gain != 64 && gain != 128) {
            throw new IllegalArgumentException("gain must be one of 1, 2, 4, 8, 16, 32, 64, 128");
        }
        int[] rates = (mclkHz >= MCLK_2_4576MHZ) ? FS_RATES_2_4MHZ : FS_RATES_1MHZ;
        boolean rateOk = false;
        for (int r : rates) {
            if (r == outputRateHz) { rateOk = true; break; }
        }
        if (!rateOk) {
            throw new IllegalArgumentException("outputRateHz must be one of " + java.util.Arrays.toString(rates));
        }

        configureClock(outputRateHz);

        int buBit = bipolar ? BIPOLAR : UNIPOLAR;
        int bufBit = buffered ? BUFFERED : UNBUFFERED;
        int gainIdx;
        switch (gain) {
            case 1:   gainIdx = 0; break;
            case 2:   gainIdx = 1; break;
            case 4:   gainIdx = 2; break;
            case 8:   gainIdx = 3; break;
            case 16:  gainIdx = 4; break;
            case 32:  gainIdx = 5; break;
            case 64:  gainIdx = 6; break;
            case 128: gainIdx = 7; break;
            default: throw new IllegalArgumentException("gain out of range");
        }
        int setup = MODE_NORMAL | GAIN_BITS[gainIdx] | buBit | bufBit | FSYNC_RUN;
        writeRegChannel(REG_SETUP, setup, ch, 1);

        if (channel == 1) {
            this.gain = gain;
            this.bipolar = bipolar;
            this.buffered = buffered;
        }
    }

    /**
     * Block until DRDY, then read the raw 16-bit code for the channel.
     *
     * @param channel 1, 2, or 3.
     * @return raw 16-bit code (0–65535).
     * @throws IOException on SPI error.
     */
    public int readRaw(int channel) throws IOException {
        int ch;
        switch (channel) {
            case 1: ch = CH1; break;
            case 2: ch = CH2; break;
            case 3: ch = CH3; break;
            default: throw new IllegalArgumentException("channel must be 1, 2, or 3");
        }
        waitDrdy();
        return readRegChannel(REG_DATA, ch, 2);
    }

    /**
     * Block until DRDY, then return the input voltage on the channel in V.
     *
     * @param channel 1, 2, or 3.
     * @return voltage in V.
     * @throws IOException on SPI error.
     */
    public float readVoltage(int channel) throws IOException {
        int code = readRaw(channel);
        return codeToVoltage(code, gain, bipolar);
    }

    /**
     * Run an internal self-calibration on the channel.
     *
     * @param channel 1, 2, or 3.
     * @throws IOException on SPI error.
     */
    public void selfCalibrate(int channel) throws IOException {
        runCalibration(channel, MODE_SELF_CAL);
    }

    /**
     * Run a zero-scale system calibration. The caller must present the
     * zero-scale voltage at AIN before calling and hold it stable until
     * this returns.
     *
     * @param channel 1, 2, or 3.
     * @throws IOException on SPI error.
     */
    public void systemCalibrateZero(int channel) throws IOException {
        runCalibration(channel, MODE_ZERO_SYS);
    }

    /**
     * Run a full-scale system calibration. The caller must present the
     * full-scale voltage at AIN before calling and hold it stable until
     * this returns.
     *
     * @param channel 1, 2, or 3.
     * @throws IOException on SPI error.
     */
    public void systemCalibrateFull(int channel) throws IOException {
        runCalibration(channel, MODE_FULL_SYS);
    }

    private void runCalibration(int channel, int mode) throws IOException {
        int ch;
        switch (channel) {
            case 1: ch = CH1; break;
            case 2: ch = CH2; break;
            case 3: ch = CH3; break;
            default: throw new IllegalArgumentException("channel must be 1, 2, or 3");
        }
        int buBit = bipolar ? BIPOLAR : UNIPOLAR;
        int bufBit = buffered ? BUFFERED : UNBUFFERED;
        int gainIdx;
        switch (gain) {
            case 1:   gainIdx = 0; break;
            case 2:   gainIdx = 1; break;
            case 4:   gainIdx = 2; break;
            case 8:   gainIdx = 3; break;
            case 16:  gainIdx = 4; break;
            case 32:  gainIdx = 5; break;
            case 64:  gainIdx = 6; break;
            case 128: gainIdx = 7; break;
            default: throw new IllegalStateException("gain out of range");
        }
        int setup = mode | GAIN_BITS[gainIdx] | buBit | bufBit | FSYNC_RUN;
        writeRegChannel(REG_SETUP, setup, ch, 1);
        waitDrdy();
    }

    /**
     * Read the 24-bit Zero-Scale Calibration Register for the channel.
     *
     * @param channel 1, 2, or 3.
     * @return 24-bit unsigned offset coefficient.
     * @throws IOException on SPI error.
     */
    public int getOffsetCalibration(int channel) throws IOException {
        int ch;
        switch (channel) {
            case 1: ch = CH1; break;
            case 2: ch = CH2; break;
            case 3: ch = CH3; break;
            default: throw new IllegalArgumentException("channel must be 1, 2, or 3");
        }
        return readRegChannel(REG_OFFSET, ch, 3);
    }

    /**
     * Write a 24-bit Zero-Scale Calibration Register for the channel.
     *
     * @param value   24-bit unsigned offset coefficient.
     * @param channel 1, 2, or 3.
     * @throws IOException on SPI error.
     */
    public void setOffsetCalibration(int value, int channel) throws IOException {
        int ch;
        switch (channel) {
            case 1: ch = CH1; break;
            case 2: ch = CH2; break;
            case 3: ch = CH3; break;
            default: throw new IllegalArgumentException("channel must be 1, 2, or 3");
        }
        writeRegChannel(REG_OFFSET, value & 0xFFFFFF, ch, 3);
    }

    /**
     * Read the 24-bit Full-Scale Calibration Register for the channel.
     *
     * @param channel 1, 2, or 3.
     * @return 24-bit unsigned gain coefficient.
     * @throws IOException on SPI error.
     */
    public int getGainCalibration(int channel) throws IOException {
        int ch;
        switch (channel) {
            case 1: ch = CH1; break;
            case 2: ch = CH2; break;
            case 3: ch = CH3; break;
            default: throw new IllegalArgumentException("channel must be 1, 2, or 3");
        }
        return readRegChannel(REG_GAIN, ch, 3);
    }

    /**
     * Write a 24-bit Full-Scale Calibration Register for the channel.
     *
     * @param value   24-bit unsigned gain coefficient.
     * @param channel 1, 2, or 3.
     * @throws IOException on SPI error.
     */
    public void setGainCalibration(int value, int channel) throws IOException {
        int ch;
        switch (channel) {
            case 1: ch = CH1; break;
            case 2: ch = CH2; break;
            case 3: ch = CH3; break;
            default: throw new IllegalArgumentException("channel must be 1, 2, or 3");
        }
        writeRegChannel(REG_GAIN, value & 0xFFFFFF, ch, 3);
    }

    /**
     * Enter standby/power-down (~10 µA). Registers are retained.
     *
     * @throws IOException on SPI error.
     */
    public void standby() throws IOException {
        connection.write(new byte[]{(byte) (commByte(REG_COMM, false, CH1) | STBY_SLEEP)});
    }

    /**
     * Exit standby and block until a fresh conversion is available.
     *
     * @throws IOException on SPI error.
     */
    public void wakeup() throws IOException {
        connection.write(new byte[]{(byte) (commByte(REG_COMM, false, CH1) | STBY_RUN)});
        waitDrdy();
    }

    /**
     * Pulse the hardware RESET line. Requires a resetPin to have been supplied.
     *
     * @throws IOException on SPI error or if no resetPin was supplied.
     */
    public void reset() throws IOException {
        if (resetPin == null) {
            throw new IllegalStateException("reset() requires a resetPin to have been supplied at construction");
        }
        resetPin.set(false);
        resetPin.set(true);
    }

    /**
     * Hardware RESET line driver — abstracts a digital output pin.
     */
    public interface OutputPin {
        /**
         * Drive the pin.
         * @param high {@code true} to drive high, {@code false} to drive low.
         * @throws IOException on GPIO error.
         */
        void set(boolean high) throws IOException;
    }
}
