package it.uhde.periph.chips.adc_dac

import groovy.transform.CompileStatic
import it.uhde.periph.connection.Connection

/**
 * Hardware RESET line driver — abstracts a digital output pin.
 */
@FunctionalInterface
interface Ad7705ResetPin {
    /**
     * Drive the pin.
     * @param high true to drive high, false to drive low.
     */
    void set(boolean high)
}

/**
 * AD7705 — 2-channel, 16-bit sigma-delta ADC with SPI interface (full driver).
 *
 * Extends Ad7705Minimal with per-channel configuration, all eight PGA gains,
 * unipolar/bipolar selection, buffered/unbuffered analog inputs, all four
 * output rates within the mclkHz family, self- and system-calibration,
 * direct access to the 24-bit calibration coefficients, standby/wakeup
 * power control, and an optional hardware reset pin.
 */
@CompileStatic
class Ad7705Full extends Ad7705Minimal {

    static final int GAIN_1   = 0
    static final int GAIN_2   = 1
    static final int GAIN_4   = 2
    static final int GAIN_8   = 3
    static final int GAIN_16  = 4
    static final int GAIN_32  = 5
    static final int GAIN_64  = 6
    static final int GAIN_128 = 7

    private final Ad7705ResetPin resetPin

    /** Construct with an optional hardware reset pin. */
    Ad7705Full(Connection connection, float vref, int mclkHz, Ad7705ResetPin resetPin) {
        super(connection, vref, mclkHz)
        this.resetPin = resetPin
    }

    /** Construct without a hardware reset pin. */
    Ad7705Full(Connection connection, float vref, int mclkHz) {
        this(connection, vref, mclkHz, null)
    }

    /**
     * Write the Setup and Clock Registers for the given channel.
     * Does not calibrate — call selfCalibrate (or one of the system-calibration
     * methods) afterward.
     */
    void configure(int channel, int gain, boolean bipolar, boolean buffered, int outputRateHz) {
        if (channel != 1 && channel != 2) {
            throw new IllegalArgumentException("channel must be 1 or 2")
        }
        if (gain != 1 && gain != 2 && gain != 4 && gain != 8 && gain != 16 && gain != 32 && gain != 64 && gain != 128) {
            throw new IllegalArgumentException("gain must be one of 1, 2, 4, 8, 16, 32, 64, 128")
        }
        int ch = (channel == 1) ? CH1 : CH2
        int[] rates = (mclkHz >= MCLK_2_4576MHZ) ? FS_RATES_2_4MHZ : FS_RATES_1MHZ
        boolean rateOk = false
        for (int r : rates) {
            if (r == outputRateHz) { rateOk = true; break }
        }
        if (!rateOk) {
            throw new IllegalArgumentException("outputRateHz must be one of ${rates}")
        }

        configureClock(outputRateHz)

        int buBit = bipolar ? BIPOLAR : UNIPOLAR
        int bufBit = buffered ? BUFFERED : UNBUFFERED
        int gainIdx
        switch (gain) {
            case 1:   gainIdx = 0; break
            case 2:   gainIdx = 1; break
            case 4:   gainIdx = 2; break
            case 8:   gainIdx = 3; break
            case 16:  gainIdx = 4; break
            case 32:  gainIdx = 5; break
            case 64:  gainIdx = 6; break
            case 128: gainIdx = 7; break
            default: throw new IllegalArgumentException("gain out of range")
        }
        int setup = MODE_NORMAL | GAIN_BITS[gainIdx] | buBit | bufBit | FSYNC_RUN
        writeRegChannel(REG_SETUP, setup, ch, 1)

        if (channel == 1) {
            this.gain = gain
            this.bipolar = bipolar
            this.buffered = buffered
        }
    }

    /** Block until DRDY, then read the raw 16-bit code for the channel. */
    int readRaw(int channel) {
        if (channel != 1 && channel != 2) {
            throw new IllegalArgumentException("channel must be 1 or 2")
        }
        int ch = (channel == 1) ? CH1 : CH2
        waitDrdy()
        return readRegChannel(REG_DATA, ch, 2)
    }

    /** Block until DRDY, then return the input voltage on the channel in V. */
    float readVoltage(int channel) {
        int code = readRaw(channel)
        return codeToVoltage(code, gain, bipolar)
    }

    /** Run an internal self-calibration on the channel. */
    void selfCalibrate(int channel) {
        runCalibration(channel, MODE_SELF_CAL)
    }

    /** Run a zero-scale system calibration. The caller must present the zero-scale voltage at AIN first. */
    void systemCalibrateZero(int channel) {
        runCalibration(channel, MODE_ZERO_SYS)
    }

    /** Run a full-scale system calibration. The caller must present the full-scale voltage at AIN first. */
    void systemCalibrateFull(int channel) {
        runCalibration(channel, MODE_FULL_SYS)
    }

    private void runCalibration(int channel, int mode) {
        if (channel != 1 && channel != 2) {
            throw new IllegalArgumentException("channel must be 1 or 2")
        }
        int ch = (channel == 1) ? CH1 : CH2
        int buBit = bipolar ? BIPOLAR : UNIPOLAR
        int bufBit = buffered ? BUFFERED : UNBUFFERED
        int gainIdx
        switch (gain) {
            case 1:   gainIdx = 0; break
            case 2:   gainIdx = 1; break
            case 4:   gainIdx = 2; break
            case 8:   gainIdx = 3; break
            case 16:  gainIdx = 4; break
            case 32:  gainIdx = 5; break
            case 64:  gainIdx = 6; break
            case 128: gainIdx = 7; break
            default: throw new IllegalStateException("gain out of range")
        }
        int setup = mode | GAIN_BITS[gainIdx] | buBit | bufBit | FSYNC_RUN
        writeRegChannel(REG_SETUP, setup, ch, 1)
        waitDrdy()
    }

    /** Read the 24-bit Zero-Scale Calibration Register for the channel. */
    int getOffsetCalibration(int channel) {
        int ch = (channel == 1) ? CH1 : CH2
        return readRegChannel(REG_OFFSET, ch, 3)
    }

    /** Write a 24-bit Zero-Scale Calibration Register for the channel. */
    void setOffsetCalibration(int value, int channel) {
        int ch = (channel == 1) ? CH1 else CH2
        writeRegChannel(REG_OFFSET, value & 0xFFFFFF, ch, 3)
    }

    /** Read the 24-bit Full-Scale Calibration Register for the channel. */
    int getGainCalibration(int channel) {
        int ch = (channel == 1) ? CH1 else CH2
        return readRegChannel(REG_GAIN, ch, 3)
    }

    /** Write a 24-bit Full-Scale Calibration Register for the channel. */
    void setGainCalibration(int value, int channel) {
        int ch = (channel == 1) ? CH1 else CH2
        writeRegChannel(REG_GAIN, value & 0xFFFFFF, ch, 3)
    }

    /** Enter standby/power-down (~10 µA). Registers are retained. */
    void standby() {
        connection.write(new byte[]{(byte) (commByte(REG_COMM, false, CH1) | STBY_SLEEP)})
    }

    /** Exit standby and block until a fresh conversion is available. */
    void wakeup() {
        connection.write(new byte[]{(byte) (commByte(REG_COMM, false, CH1) | STBY_RUN)})
        waitDrdy()
    }

    /** Pulse the hardware RESET line. Requires a resetPin to have been supplied. */
    void reset() {
        if (resetPin == null) {
            throw new IllegalStateException("reset() requires a resetPin to have been supplied at construction")
        }
        resetPin.set(false)
        resetPin.set(true)
    }
}
